import SwiftUI
import UIKit
import UniformTypeIdentifiers

@MainActor
struct AudioCompanionView: View {
    @ObservedObject var model: CompanionModel
    let onClose: () -> Void
    let onFollowUp: (AudioReadResult) -> Void
    @StateObject private var recorder = AudioRecorder()
    @StateObject private var reading = AudioReadStore()
    @State private var selectedURL: URL?
    @State private var sourceName = "设备音频"
    @State private var errorMessage: String?
    @State private var saveWarning: String?
    @State private var importingFile = false
    @State private var preparing = false
    @State private var closing = false
    @State private var work: Task<Void, Never>?
    @State private var actionID: UUID?
    @State private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private var busy: Bool { preparing || closing || reading.isRunning || recorder.isRequesting || recorder.isFinishing }
    private var hasRecording: Bool { selectedURL != nil || recorder.fileURL != nil }
    private var timeLabel: String {
        let seconds = Int(recorder.elapsedSeconds)
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { close() } label: { Image(systemName: "chevron.left") }
                    .help("返回聊天并停止陪听").accessibilityLabel("返回聊天并停止陪听").disabled(closing)
                Text("陪听").font(.headline)
                Spacer()
                Text(sourceName).font(.caption).foregroundStyle(.secondary)
                Menu {
                    Button { startRecording(microphone: false) } label: { Label("听设备音频", systemImage: "speaker.wave.2") }
                    Button { startRecording(microphone: true) } label: { Label("用麦克风听", systemImage: "mic") }
                    Button { importingFile = true } label: { Label("导入音频并解释", systemImage: "doc.badge.plus") }
                } label: { Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44) }
                .help("其他音频来源").accessibilityLabel("其他音频来源").disabled(busy || recorder.isRecording)
            }.padding(.horizontal, 14)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if reading.transcript.isEmpty && reading.answer.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Label("听懂这一段", systemImage: "waveform").font(.title3)
                            Text("在旁边播放视频，点“听一段”；听完点“结束并解释”，原文、翻译和解释会显示在这里。")
                                .foregroundStyle(.secondary)
                            Text("每段最多 2 分钟。到时先停在本机，点击解释才上传。")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if !reading.transcript.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("原文", systemImage: "text.quote").font(.headline)
                            Text(verbatim: reading.transcript).textSelection(.enabled)
                        }
                    }
                    if !reading.answer.isEmpty { MarkdownAnswerView(text: reading.answer) }
                    if let result = reading.result {
                        if !result.answer.complete {
                            Label("解释未完成", systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.secondary)
                        }
                        if result.answer.searchState != .notUsed {
                            Text(result.answer.searchState.label).font(.caption).foregroundStyle(.secondary)
                        }
                        ForEach(result.answer.citations) { citation in
                            Link(destination: citation.url) {
                                Label(citation.title.isEmpty ? citation.url.host ?? "查看来源" : citation.title, systemImage: "link")
                            }
                        }
                        Button { onFollowUp(result) } label: { Label("追问这一段", systemImage: "text.bubble") }
                            .disabled(busy || recorder.isRecording)
                    }
                    if let failure = reading.failure { Text(failure).font(.footnote).foregroundStyle(.orange) }
                    if let error = errorMessage ?? (sourceName == "导入音频" ? nil : recorder.lastError) {
                        Text(error).font(.footnote).foregroundStyle(.orange)
                    }
                    if let saveWarning { Text(saveWarning).font(.footnote).foregroundStyle(.orange) }
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
            controls
        }
        .fileImporter(isPresented: $importingFile, allowedContentTypes: [.audio]) { result in importAudio(result) }
        .onChange(of: model.sessionID) { _, _ in close() }
        .onDisappear {
            actionID = nil
            work?.cancel()
            reading.cancel()
            endBackgroundTask()
            let ownedFile = selectedURL
            Task { await recorder.cancel(); if let ownedFile { try? FileManager.default.removeItem(at: ownedFile) } }
        }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
            if reading.isRunning {
                HStack {
                    ProgressView().controlSize(.small)
                    Text(reading.phase == .transcribing ? "正在转写原文…" : (reading.status?.label ?? "正在翻译和解释…"))
                        .font(.subheadline)
                    Spacer()
                    Button { cancelProcessing() } label: { Image(systemName: "stop.circle.fill").font(.title2) }
                        .help("停止处理，保留已收到的文字").accessibilityLabel("停止处理")
                }
            } else if busy {
                HStack { ProgressView(); Text(recorder.isRequesting ? "请在系统窗口选择共享来源…" : "正在处理音频…").font(.subheadline) }
            } else if recorder.isRecording {
                HStack {
                    Label(timeLabel, systemImage: "record.circle").monospacedDigit().foregroundStyle(.red)
                    Spacer()
                    Button { explain() } label: { Label("结束并解释", systemImage: "stop.fill") }
                        .buttonStyle(.borderedProminent)
                }
            } else if hasRecording && reading.result?.answer.complete != true {
                Button { explain() } label: {
                    Label(reading.transcript.isEmpty ? "解释本段" : "重试解释", systemImage: "text.bubble")
                        .frame(maxWidth: .infinity)
                }.buttonStyle(.borderedProminent)
            } else {
                Button { startRecording(microphone: false) } label: {
                    Label(reading.transcript.isEmpty ? "听一段" : "再听一段", systemImage: "waveform")
                        .frame(maxWidth: .infinity)
                }.buttonStyle(.borderedProminent)
            }
            Text("点击解释会上传本段音频，自动转写、翻译和解释。")
                .font(.caption).foregroundStyle(.secondary)
        }.padding(14).background(.background)
    }

    private func validateSettings() throws {
        guard model.provider == .openAI else { throw ClientError.invalidSettings }
        guard model.keyAvailable else { throw ClientError.missingKey }
        guard model.openAISettings.validationMessage == nil else { throw ClientError.invalidSettings }
    }

    private func startRecording(microphone: Bool) {
        guard !busy, !recorder.isRecording else { return }
        do { try validateSettings() } catch { errorMessage = error.localizedDescription; return }
        actionID = UUID()
        let token = actionID
        preparing = true
        work = Task { @MainActor in
            defer { if actionID == token { preparing = false; work = nil } }
            await recorder.cancel()
            guard !Task.isCancelled, actionID == token, !recorder.isRecording else { return }
            clearSelectedAudio()
            reading.reset()
            errorMessage = nil; saveWarning = nil
            sourceName = microphone ? "麦克风" : "设备音频"
            do {
                if microphone { try await recorder.startMicrophone() }
                else { try await recorder.start() }
            } catch {
                if !Task.isCancelled, actionID == token { errorMessage = (error as? AudioError ?? .recording).localizedDescription }
            }
        }
    }

    private func explain() {
        guard !busy, recorder.isRecording || hasRecording else { return }
        let key: String
        do {
            try validateSettings()
            guard let savedKey = try model.credentials.read(.openAI), !savedKey.isEmpty else { throw ClientError.missingKey }
            key = savedKey
        } catch { errorMessage = (error as? ClientError ?? .keychain).localizedDescription; return }
        let settings = model.openAISettings
        let token = UUID()
        actionID = token
        preparing = true
        errorMessage = nil
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "AlwaySide audio explanation") {
            cancelProcessing()
            errorMessage = "系统后台时间已结束，已停止处理；原文仍可查看或手动重试。"
        }
        work = Task { @MainActor in
            defer {
                if actionID == token { preparing = false; work = nil; endBackgroundTask() }
            }
            do {
                let file: URL
                if recorder.isRecording { file = try await recorder.stop() }
                else if let available = selectedURL ?? recorder.fileURL { file = available }
                else { throw AudioError.empty }
                guard !Task.isCancelled, actionID == token else { return }
                selectedURL = file
                await reading.process(transcribe: {
                    try await AudioTranscriptionClient().transcribe(fileURL: file, key: key)
                }, explain: { prompt, onEvent in
                    try await VisionClient().answer(provider: .openAI, key: key, question: prompt,
                        history: [], openAISettings: settings, onEvent: onEvent)
                })
                guard !Task.isCancelled, actionID == token else { return }
                saveResult()
            } catch {
                if !Task.isCancelled, actionID == token { errorMessage = (error as? AudioError ?? .recording).localizedDescription }
            }
        }
    }

    private func cancelProcessing() {
        actionID = nil
        work?.cancel(); work = nil
        reading.cancel()
        preparing = false
        endBackgroundTask()
        saveResult()
    }

    private func saveResult() {
        if let result = reading.result { saveWarning = model.saveAudioRead(result) }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func close() {
        guard !closing else { return }
        closing = true
        cancelProcessing()
        Task { @MainActor in
            await recorder.cancel()
            closing = false
            guard !recorder.isRecording else { errorMessage = recorder.lastError; return }
            clearSelectedAudio()
            onClose()
        }
    }

    private func clearSelectedAudio() {
        if let selectedURL { try? FileManager.default.removeItem(at: selectedURL) }
        selectedURL = nil
    }

    private func importAudio(_ result: Result<URL, Error>) {
        guard case .success(let url) = result, !busy, !recorder.isRecording else { return }
        do { try validateSettings() } catch { errorMessage = error.localizedDescription; return }
        let token = UUID()
        actionID = token
        preparing = true
        work = Task { @MainActor in
            defer { if actionID == token { preparing = false; work = nil } }
            await recorder.cancel()
            guard !Task.isCancelled, actionID == token, !recorder.isRecording else { return }
            // Selecting a new file invalidates the old clip before any fallible read.
            clearSelectedAudio()
            reading.reset()
            sourceName = "导入音频"
            errorMessage = nil; saveWarning = nil
            do {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
                guard size > 0, size <= AudioTranscriptionClient.maximumBytes else { throw AudioError.tooLarge }
                let copy = FileManager.default.temporaryDirectory.appendingPathComponent("alwayside-import-\(UUID().uuidString).\(url.pathExtension)")
                do { try FileManager.default.copyItem(at: url, to: copy) }
                catch { try? FileManager.default.removeItem(at: copy); throw error }
                selectedURL = copy
                preparing = false
                work = nil
                explain()
            } catch { errorMessage = (error as? AudioError ?? .unsupported).localizedDescription }
        }
    }
}
