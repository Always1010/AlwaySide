import AppIntents
import Combine
import SwiftUI
import UIKit

/// The shortcut owns the recording, rather than a view that disappears on backgrounding.
@MainActor
final class ShortcutListening: ObservableObject {
    static let shared = ShortcutListening()
    enum Phase: Equatable { case idle, requesting, recording, processing, failed }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var failure: String?
    let recorder = AudioRecorder()
    let reading = AudioReadStore()
    private var busy = false
    private var work: Task<Void, Never>?
    private var generation = UUID()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let interruptedKey = "shortcutListeningWasRecording"
    private var interrupted: Bool
    private var pendingFile = false
    private var handoff: AudioReadResult?
    static let didFinish = Notification.Name("AlwaySideShortcutListeningFinished")

    private init() { interrupted = UserDefaults.standard.bool(forKey: interruptedKey) }

    var isVisible: Bool { phase != .idle }

    /// Returns true only for the explicit end gesture. An automatic time limit never uploads.
    func toggle() async throws -> Bool {
        guard !busy, phase != .processing, !recorder.isRequesting, !recorder.isFinishing else { throw AudioError.busy }
        if interrupted {
            interrupted = false
            UserDefaults.standard.set(false, forKey: interruptedKey)
            throw ListeningError.interrupted
        }
        busy = true
        defer { busy = false }
        if pendingFile {
            let file = try await recorder.stop()
            try Task.checkCancellation()
            let key: String
            guard let saved = try CredentialStore().read(.openAI), !saved.isEmpty else { throw ClientError.missingKey }
            key = saved
            let settings = try loadSettings()
            pendingFile = false
            UserDefaults.standard.set(false, forKey: interruptedKey)
            phase = .processing
            failure = nil
            let token = UUID()
            generation = token
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Shortcut audio explanation") { [weak self] in
                self?.cancelProcessing()
            }
            work = Task { @MainActor [weak self] in
                guard let self else { return }
                await self.reading.process(transcribe: {
                    try await AudioTranscriptionClient().transcribe(fileURL: file, key: key)
                }, explain: { prompt, onEvent in
                    try await VisionClient().answer(provider: .openAI, key: key, question: prompt,
                        history: [], openAISettings: settings, onEvent: onEvent)
                })
                guard !Task.isCancelled, self.generation == token else { return }
                self.failure = self.reading.failure
                self.phase = self.failure == nil ? .idle : .failed
                if let result = self.reading.result {
                    self.handoff = result
                    NotificationCenter.default.post(name: Self.didFinish, object: nil)
                }
                self.work = nil
                self.endBackgroundTask()
                // Keep the local file for an explicit retry if either stage failed.
                if self.failure == nil { await self.recorder.cancel() }
                else { self.pendingFile = true }
            }
            return true
        }
        _ = try loadSettings()
        guard try CredentialStore().read(.openAI)?.isEmpty == false else { throw ClientError.missingKey }
        await recorder.cancel()
        guard !recorder.isRecording else { throw AudioError.busy }
        reading.reset()
        failure = nil
        handoff = nil
        phase = .requesting
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                defer { group.cancelAll() }
                group.addTask { @MainActor [recorder] in try await recorder.start() }
                group.addTask {
                    try await Task.sleep(nanoseconds: 30_000_000_000)
                    throw AudioError.unavailable
                }
                _ = try await group.next()
            }
            try Task.checkCancellation()
            phase = .recording
            pendingFile = true
            UserDefaults.standard.set(true, forKey: interruptedKey)
            return false
        } catch {
            await recorder.cancel()
            failure = (error as? AudioError ?? .unavailable).localizedDescription
            phase = .failed
            throw error
        }
    }

    func takeHandoff() -> AudioReadResult? {
        defer { handoff = nil }
        return handoff
    }

    func cancelProcessing(keepResult: Bool = true) {
        generation = UUID()
        work?.cancel(); work = nil
        reading.cancel()
        phase = .failed
        pendingFile = recorder.fileURL != nil
        failure = "处理已停止，已收到的原文仍可查看；再次点陪听可重试。"
        endBackgroundTask()
        if keepResult, let result = reading.result {
            handoff = result
            NotificationCenter.default.post(name: Self.didFinish, object: nil)
        } else {
            handoff = nil
            if !keepResult {
                pendingFile = false
                phase = .idle
                failure = nil
                reading.reset()
            }
        }
    }

    func reportFailure(_ error: Error) {
        guard !busy, phase != .processing else { return }
        failure = (error as? AudioError)?.localizedDescription ?? (error as? ClientError)?.localizedDescription ?? (error as? ListeningError)?.localizedDescription ?? "陪听未能完成，请重试。"
        phase = .failed
    }

    func discard() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        cancelProcessing(keepResult: false)
        await recorder.cancel()
        guard !recorder.isRecording else { phase = .failed; failure = recorder.lastError; return }
        pendingFile = false
        UserDefaults.standard.set(false, forKey: interruptedKey)
        phase = .idle
        failure = nil
        handoff = nil
        reading.reset()
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func loadSettings() throws -> OpenAISettings {
        guard (ModelProvider(rawValue: UserDefaults.standard.string(forKey: "provider") ?? "") ?? .openAI) == .openAI else {
            throw ClientError.invalidSettings
        }
        let settings: OpenAISettings
        if let data = UserDefaults.standard.data(forKey: "openAISettings") {
            guard let stored = try? JSONDecoder().decode(OpenAISettings.self, from: data) else { throw ClientError.invalidSettings }
            settings = stored.migratingShippedDefaults()
        } else { settings = .defaults }
        guard settings.validationMessage == nil else { throw ClientError.invalidSettings }
        return settings
    }

    enum ListeningError: LocalizedError {
        case interrupted
        var errorDescription: String? { "上次陪听已被系统中断，无法找回那段录音。再次点陪听会开始新的一段。" }
    }
}

struct ToggleShortcutListeningIntent: AppIntent, TargetContentProvidingIntent {
    static let title: LocalizedStringResource = "陪听（开始或结束并解释）"
    static let description = IntentDescription("第一次授权录制设备音频，第二次停止并上传转写、翻译和解释，同时尝试恢复原助手窗口。每段最多2分钟，不自动上传。")
    static let supportedModes: IntentModes = .foreground(.dynamic)
    var contentIdentifier: String { WindowRecovery.contentIdentifier }

    @MainActor
    func perform() async throws -> some IntentResult {
        let ended: Bool
        do { ended = try await ShortcutListening.shared.toggle() }
        catch {
            ShortcutListening.shared.reportFailure(error)
            throw error
        }
        if ended { try WindowRecovery.shared.requestExistingWindow() }
        return .result()
    }
}

struct ShortcutListeningPanel: View {
    @ObservedObject var session: ShortcutListening
    @ObservedObject var recorder: AudioRecorder
    @ObservedObject var reading: AudioReadStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Label("小球陪听", systemImage: "waveform").font(.headline)
                if session.phase == .requesting {
                    HStack { ProgressView(); Text("请在系统选择器授权设备音频…") }
                } else if session.phase == .recording {
                    Text(recorder.isRecording ? "正在听 · \(Int(recorder.elapsedSeconds)) 秒" : "本段已停止，等待你确认解释")
                    Text("回到视频继续播放；再点小球菜单里的陪听，结束这一段并解释。每段最多2分钟。")
                        .foregroundStyle(.secondary)
                } else if session.phase == .processing {
                    HStack {
                        ProgressView()
                        Text(reading.phase == .transcribing ? "正在转写原文…" : "正在翻译和解释…")
                    }
                    Button { session.cancelProcessing() } label: { Label("停止处理", systemImage: "stop.circle") }
                }
                if !reading.transcript.isEmpty {
                    Text("原文").font(.headline)
                    Text(verbatim: reading.transcript).textSelection(.enabled)
                }
                if !reading.answer.isEmpty { MarkdownAnswerView(text: reading.answer) }
                if let failure = session.failure ?? recorder.lastError { Text(failure).foregroundStyle(.orange) }
                Button { Task { await session.discard() } } label: {
                    Label(session.phase == .recording ? "放弃这段录音" : "关闭陪听状态", systemImage: "xmark.circle")
                }.disabled(session.phase == .requesting)
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
