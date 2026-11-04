import Combine
import Foundation
import UIKit

/// A window owns its listening session, including work that continues while its view is backgrounded.
@MainActor
final class WindowListening: ObservableObject {
    enum Phase: Equatable { case idle, requesting, recording, ready, processing, failed }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var failure: String?
    let recorder = AudioRecorder()
    let reading = AudioReadStore()
    var onResult: ((AudioReadResult) -> Void)?

    private enum Operation { case starting, processing, importing, discarding }
    private var operation: Operation?
    private var work: Task<Void, Never>?
    private var observationTask: Task<Void, Never>?
    private var subscriptions = Set<AnyCancellable>()
    private var generation = UUID()
    private var importedFile: URL?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    /// Screen and device-audio capture share the system's one content-sharing picker.
    /// A ready clip remains reserved until it is submitted or explicitly discarded.
    var blocksCapture: Bool {
        operation == .starting || operation == .discarding || recorder.isRequesting ||
        recorder.isRecording || recorder.isFinishing || hasPendingRecording
    }

    /// Recording itself remains actionable: the next listening gesture ends the segment.
    var isBusy: Bool {
        operation != nil || recorder.isRequesting || recorder.isFinishing || reading.isRunning
    }

    var hasPendingRecording: Bool {
        recorder.isRecording || recorder.fileURL != nil || importedFile != nil
    }

    init() {
        recorder.objectWillChange.sink { [weak self] in
            guard let self else { return }
            self.objectWillChange.send()
            // @Published emits before its value changes. Reconcile on the next main-actor turn.
            self.observationTask?.cancel()
            self.observationTask = Task { @MainActor [weak self] in
                guard !Task.isCancelled, let self else { return }
                self.reconcileRecorder()
                self.observationTask = nil
            }
        }.store(in: &subscriptions)
        reading.objectWillChange.sink { [weak self] in
            self?.objectWillChange.send()
        }.store(in: &subscriptions)
    }

    func start(microphone: Bool = false, settings: OpenAISettings, key: String) {
        guard !isBusy, !hasPendingRecording else { return }
        guard validate(settings: settings, key: key) else { return }
        let token = UUID()
        generation = token
        operation = .starting
        phase = .requesting
        failure = nil
        work = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.completeOperation(token: token) }
            await self.recorder.cancel()
            guard self.isCurrent(token) else { return }
            guard !self.recorder.isRecording else {
                self.phase = .recording
                self.failure = self.recorder.lastError ?? AudioError.recording.localizedDescription
                return
            }
            self.clearImportedFile()
            self.reading.reset()
            do {
                if microphone { try await self.recorder.startMicrophone() }
                else { try await self.recorder.start() }
                guard self.isCurrent(token) else { return }
                self.phase = self.recorder.isRecording ? .recording : self.hasPendingRecording ? .ready : .failed
                if self.phase == .failed { self.failure = self.recorder.lastError ?? AudioError.recording.localizedDescription }
            } catch {
                guard self.isCurrent(token) else { return }
                await self.recorder.cancel()
                guard self.isCurrent(token) else { return }
                if let audioError = error as? AudioError, case .cancelled = audioError {
                    self.phase = self.recorder.isRecording ? .recording : .idle
                    self.failure = self.recorder.isRecording ? self.recorder.lastError : nil
                } else {
                    self.phase = self.recorder.isRecording ? .recording : .failed
                    self.failure = self.recorder.lastError ?? self.message(for: error)
                }
            }
        }
    }

    /// Only an explicit finish/retry/import gesture starts an upload.
    func finish(settings: OpenAISettings, key: String) {
        guard !isBusy, hasPendingRecording else { return }
        guard validate(settings: settings, key: key) else { return }
        launchProcessing(settings: settings, key: key)
    }

    func retry(settings: OpenAISettings, key: String) {
        guard !isBusy, !recorder.isRecording, reading.result?.answer.complete != true,
              hasPendingRecording || !reading.transcript.isEmpty else { return }
        guard validate(settings: settings, key: key) else { return }
        launchProcessing(settings: settings, key: key)
    }

    func cancelProcessing() {
        guard operation == .processing || operation == .importing || reading.isRunning else { return }
        // The response has already been handed off; let the brief local-file cleanup finish.
        if operation == .processing, reading.result?.answer.complete == true {
            endBackgroundTask()
            return
        }
        generation = UUID()
        work?.cancel()
        work = nil
        operation = nil
        reading.cancel()
        endBackgroundTask()
        failure = "处理已停止，已收到的原文和回答仍可查看；可手动重试。"
        phase = recorder.isRecording ? .recording : .failed
    }

    func discard() async {
        guard operation != .discarding else { return }
        let token = UUID()
        generation = token
        work?.cancel()
        work = nil
        reading.cancel()
        endBackgroundTask()
        operation = .discarding
        defer { completeOperation(token: token) }
        await recorder.cancel()
        guard generation == token else { return }
        guard !recorder.isRecording else {
            phase = .recording
            failure = recorder.lastError ?? AudioError.recording.localizedDescription
            return
        }
        clearImportedFile()
        reading.reset()
        phase = .idle
        failure = nil
    }

    /// Copy a security-scoped import into owned temporary storage before starting the pipeline.
    func processFile(url: URL, settings: OpenAISettings, key: String) {
        guard !isBusy, !hasPendingRecording else { return }
        guard validate(settings: settings, key: key) else { return }
        let token = UUID()
        generation = token
        operation = .importing
        phase = .processing
        failure = nil
        work = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.recorder.cancel()
            guard self.isCurrent(token) else { return }
            guard !self.recorder.isRecording else {
                self.failure = self.recorder.lastError ?? AudioError.recording.localizedDescription
                self.phase = .recording
                self.completeOperation(token: token)
                return
            }
            self.clearImportedFile()
            self.reading.reset()
            do {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
                guard size > 0 else { throw AudioError.empty }
                guard size <= AudioTranscriptionClient.maximumBytes else { throw AudioError.tooLarge }
                let suffix = url.pathExtension.isEmpty ? "audio" : url.pathExtension
                let copy = FileManager.default.temporaryDirectory
                    .appendingPathComponent("alwayside-import-\(UUID().uuidString).\(suffix)")
                do { try FileManager.default.copyItem(at: url, to: copy) }
                catch { try? FileManager.default.removeItem(at: copy); throw error }
                self.clearImportedFile()
                self.importedFile = copy
                self.reading.reset()
            } catch {
                guard self.isCurrent(token) else { return }
                self.failure = self.message(for: error)
                self.phase = .failed
                self.completeOperation(token: token)
                return
            }
            guard self.isCurrent(token) else { return }
            self.completeOperation(token: token)
            self.launchProcessing(settings: settings, key: key)
        }
    }

    private func launchProcessing(settings: OpenAISettings, key: String) {
        let token = UUID()
        generation = token
        operation = .processing
        phase = .processing
        failure = nil
        beginBackgroundTask(token: token)
        work = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.completeOperation(token: token) }
            do {
                let file: URL?
                if self.recorder.isRecording { file = try await self.recorder.stop() }
                else { file = self.importedFile ?? self.recorder.fileURL }
                guard self.isCurrent(token) else { return }
                guard file != nil || !self.reading.transcript.isEmpty else { throw AudioError.empty }
                await self.reading.process(transcribe: {
                    guard let file else { throw AudioError.empty }
                    return try await AudioTranscriptionClient().transcribe(fileURL: file, key: key)
                }, explain: { prompt, onEvent in
                    try await VisionClient().answer(provider: .openAI, key: key, question: prompt,
                        history: [], openAISettings: settings, onEvent: onEvent)
                })
                guard self.isCurrent(token) else { return }
                self.failure = self.reading.failure
                // One callback per completed request, including useful incomplete responses.
                // Cancellation keeps partial text in reading without handing it off twice.
                if let result = self.reading.result { self.onResult?(result) }
                guard self.isCurrent(token) else { return }
                if self.reading.result?.answer.complete == true {
                    await self.recorder.cancel()
                    guard self.isCurrent(token) else { return }
                    self.clearImportedFile()
                    self.phase = .idle
                } else {
                    self.phase = .failed
                    if self.failure == nil { self.failure = "音频处理未完成，请手动重试。" }
                }
            } catch {
                guard self.isCurrent(token) else { return }
                self.failure = self.message(for: error)
                self.phase = self.recorder.isRecording ? .recording : .failed
            }
        }
    }

    private func reconcileRecorder() {
        guard operation == nil else { return }
        if recorder.isRequesting { phase = .requesting }
        else if recorder.isRecording { phase = .recording }
        else if hasPendingRecording {
            if phase != .failed { phase = .ready }
        } else if phase == .recording || phase == .requesting || phase == .ready {
            failure = recorder.lastError ?? "录音已中断，请重新开始。"
            phase = .failed
        }
        if let error = recorder.lastError, phase != .idle { failure = error }
    }

    private func validate(settings: OpenAISettings, key: String) -> Bool {
        if key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            failure = ClientError.missingKey.localizedDescription
        } else if let message = settings.validationMessage {
            failure = message
        } else { return true }
        phase = recorder.isRecording ? .recording : hasPendingRecording ? .ready : .failed
        return false
    }

    private func isCurrent(_ token: UUID) -> Bool {
        generation == token && !Task.isCancelled
    }

    private func completeOperation(token: UUID) {
        guard generation == token else { return }
        operation = nil
        work = nil
        endBackgroundTask()
        reconcileRecorder()
    }

    private func beginBackgroundTask(token: UUID) {
        endBackgroundTask()
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "AlwaySide window audio explanation") { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.generation == token else { return }
                if self.reading.result?.answer.complete == true {
                    self.endBackgroundTask()
                    return
                }
                self.cancelProcessing()
                self.failure = "系统后台时间已结束，已停止处理；原文和回答仍可查看或手动重试。"
            }
        }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func clearImportedFile() {
        if let importedFile { try? FileManager.default.removeItem(at: importedFile) }
        importedFile = nil
    }

    private func message(for error: Error) -> String {
        if let audio = error as? AudioError { return audio.localizedDescription }
        if let client = error as? ClientError { return client.localizedDescription }
        return "音频处理未完成，请重试；不会自动重新上传。"
    }
}
