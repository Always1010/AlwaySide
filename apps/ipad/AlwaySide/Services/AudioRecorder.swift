import AVFoundation
import Combine
import Foundation
@preconcurrency import ScreenCaptureKit

/// All writer state is confined to queue. Media buffers are consumed on the stream's
/// sampleHandlerQueue and never sent through a Task or retained in an unbounded queue.
private final class AudioSampleWriter: NSObject, SCStreamOutput, @unchecked Sendable {
    let queue = DispatchQueue(label: "local.alwayside.audio-writer", qos: .userInitiated)
    let url: URL
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var samples = 0
    private var bytes = 0
    private var blockedSince: TimeInterval?
    private var accepting = true
    private var failed = false
    private let deadline = ProcessInfo.processInfo.systemUptime + 120
    private let onFailure: @Sendable () -> Void
    private let onLimit: @Sendable () -> Void

    init(url: URL, onFailure: @escaping @Sendable () -> Void, onLimit: @escaping @Sendable () -> Void) {
        self.url = url
        self.onFailure = onFailure
        self.onLimit = onLimit
        super.init()
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .audio, accepting, CMSampleBufferIsValid(sampleBuffer),
              CMSampleBufferDataIsReady(sampleBuffer), CMSampleBufferGetNumSamples(sampleBuffer) > 0 else { return }
        guard ProcessInfo.processInfo.systemUptime < deadline else {
            accepting = false
            onLimit()
            return
        }
        do {
            if writer == nil {
                let output = try AVAssetWriter(outputURL: url, fileType: .m4a)
                let track = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                    AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48000,
                    AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000
                ], sourceFormatHint: CMSampleBufferGetFormatDescription(sampleBuffer))
                track.expectsMediaDataInRealTime = true
                guard output.canAdd(track) else { throw AudioError.recording }
                output.add(track)
                guard output.startWriting() else { throw AudioError.recording }
                output.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
                writer = output
                input = track
            }
            guard let writer, let input, writer.status == .writing else { throw AudioError.recording }
            // Permit brief encoder startup/backpressure without keeping any media backlog.
            // Sustained overload fails visibly instead of silently losing the recording.
            if !input.isReadyForMoreMediaData {
                let now = ProcessInfo.processInfo.systemUptime
                if let blockedSince, now - blockedSince >= 0.5 { throw AudioError.recording }
                if blockedSince == nil { blockedSince = now }
                return
            }
            blockedSince = nil
            guard input.append(sampleBuffer) else { throw AudioError.recording }
            samples += 1
            bytes += CMSampleBufferGetTotalSampleSize(sampleBuffer)
            guard bytes <= 64 * 1024 * 1024 else { throw AudioError.tooLarge }
        } catch {
            failed = true
            accepting = false
            onFailure()
        }
    }

    func finish(discard: Bool) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                self.accepting = false
                guard !discard, !self.failed, self.samples > 0,
                      let writer = self.writer, let input = self.input, writer.status == .writing else {
                    self.writer?.cancelWriting()
                    try? FileManager.default.removeItem(at: self.url)
                    continuation.resume(throwing: discard ? AudioError.cancelled : self.failed ? .recording : .empty)
                    return
                }
                input.markAsFinished()
                writer.finishWriting {
                    guard writer.status == .completed else {
                        try? FileManager.default.removeItem(at: self.url)
                        continuation.resume(throwing: AudioError.recording)
                        return
                    }
                    continuation.resume(returning: self.url)
                }
            }
        }
    }
}

private struct AudioPickerSelection: @unchecked Sendable {
    let filter: SCContentFilter
}

private final class AudioPickerObserver: NSObject, SCContentSharingPickerObserver {
    let selected: @Sendable (AudioPickerSelection) -> Void
    let cancelled: @Sendable () -> Void
    let failed: @Sendable () -> Void

    init(selected: @escaping @Sendable (AudioPickerSelection) -> Void,
         cancelled: @escaping @Sendable () -> Void, failed: @escaping @Sendable () -> Void) {
        self.selected = selected
        self.cancelled = cancelled
        self.failed = failed
        super.init()
    }

    func contentSharingPicker(_ picker: SCContentSharingPicker,
                              didUpdateWith filter: SCContentFilter, for stream: SCStream?) {
        // Selection changes for an already running stream must not silently change the source.
        guard stream == nil else { return }
        selected(AudioPickerSelection(filter: filter))
    }
    func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) { cancelled() }
    func contentSharingPickerStartDidFailWithError(_ error: any Error) { failed() }
}

/// A user-started bounded recording. The device mode subscribes only to audio:
/// no screen output, screenshot, video encoder, camera or microphone is attached.
@MainActor
final class AudioRecorder: NSObject, ObservableObject {
    static let maximumDuration: TimeInterval = 120
    @Published private(set) var isRecording = false
    @Published private(set) var isRequesting = false
    @Published private(set) var elapsedSeconds: TimeInterval = 0
    @Published private(set) var fileURL: URL?
    @Published private(set) var lastError: String?
    @Published private(set) var isFinishing = false

    private let picker = SCContentSharingPicker.shared
    private var observer: AudioPickerObserver?
    private var stream: SCStream?
    private var sink: AudioSampleWriter?
    private var microphone: AVAudioRecorder?
    private var microphoneURL: URL?
    private var startContinuation: CheckedContinuation<Void, Error>?
    private var startTask: Task<Void, Never>?
    private var stopTask: Task<URL, Error>?
    private var cancelTask: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var generation = UUID()
    private var startedAt: Date?

    func start() async throws {
        guard !isRecording, !isRequesting, !isFinishing, stream == nil,
              cancelTask == nil, stopTask == nil else { throw AudioError.busy }
        clearFinishedFile()
        lastError = nil
        elapsedSeconds = 0
        isRequesting = true
        let id = UUID()
        generation = id
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                startContinuation = continuation
                var config = SCContentSharingPickerConfiguration()
                config.showsMicrophoneControl = false
                config.showsCameraControl = false
                picker.defaultConfiguration = config
                let observer = AudioPickerObserver(selected: { [weak self] selection in
                    Task { @MainActor [weak self] in
                        guard let self, self.generation == id, self.isRequesting, self.startTask == nil else { return }
                        self.startTask = Task { [weak self] in await self?.beginDeviceAudio(selection, id: id) }
                    }
                }, cancelled: { [weak self] in
                    Task { @MainActor [weak self] in
                        guard let self, self.generation == id, self.stream == nil else { return }
                        self.finishStart(error: AudioError.cancelled)
                    }
                }, failed: { [weak self] in
                    Task { @MainActor [weak self] in
                        guard let self, self.generation == id, self.stream == nil else { return }
                        self.finishStart(error: AudioError.unavailable)
                    }
                })
                self.observer = observer
                picker.add(observer)
                picker.isActive = true
                picker.present()
            }
        } onCancel: {
            Task { @MainActor [weak self] in await self?.cancel() }
        }
    }

    private func beginDeviceAudio(_ selection: AudioPickerSelection, id: UUID) async {
        let url = temporaryURL()
        let sink = AudioSampleWriter(url: url, onFailure: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.generation == id else { return }
                self.lastError = AudioError.recording.localizedDescription
                await self.cancel()
            }
        }, onLimit: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.generation == id, self.isRecording else { return }
                _ = try? await self.stop()
            }
        })
        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.sampleRate = 48000
        config.channelCount = 1
        config.excludesCurrentProcessAudio = true
        let stream = SCStream(filter: selection.filter, configuration: config, delegate: self)
        self.stream = stream
        self.sink = sink
        do {
            try stream.addStreamOutput(sink, type: .audio, sampleHandlerQueue: sink.queue)
            try await stream.startCapture()
            guard generation == id, !Task.isCancelled else {
                // Cancel awaits this task, then stops the retained stream. Keeping
                // its handle allows another stop attempt if the system rejects one.
                return
            }
            isRecording = true
            finishStart(error: nil, keepPicker: true)
            beginTimer()
        } catch {
            guard generation == id else { return }
            finishStart(error: AudioError.unavailable)
            try? await stream.stopCapture()
            try? stream.removeStreamOutput(sink, type: .audio)
            _ = try? await sink.finish(discard: true)
            self.stream = nil
            self.sink = nil
        }
        if generation == id { startTask = nil }
    }

    func startMicrophone() async throws {
        guard !isRecording, !isRequesting, !isFinishing, stream == nil,
              cancelTask == nil, stopTask == nil else { throw AudioError.busy }
        clearFinishedFile()
        lastError = nil
        elapsedSeconds = 0
        isRequesting = true
        let id = UUID()
        generation = id
        let allowed = await AVAudioApplication.requestRecordPermission()
        defer { if generation == id { isRequesting = false } }
        guard generation == id, !Task.isCancelled else { throw AudioError.cancelled }
        isRequesting = false
        guard allowed else { throw AudioError.permission }
        let audioSession = AVAudioSession.sharedInstance()
        do {
            // mixWithOthers permits playback in the other app; this remains microphone input.
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .defaultToSpeaker])
            try audioSession.setActive(true)
            let url = temporaryURL()
            microphoneURL = url
            let recorder = try AVAudioRecorder(url: url, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 24000,
                AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000
            ])
            microphone = recorder
            recorder.delegate = self
            guard recorder.prepareToRecord(), recorder.record(forDuration: Self.maximumDuration) else { throw AudioError.recording }
            isRecording = true
            beginTimer()
        } catch {
            await cancel()
            throw AudioError.recording
        }
    }

    func stop() async throws -> URL {
        if let stopTask { return try await stopTask.value }
        guard cancelTask == nil else { throw AudioError.busy }
        let task = Task { @MainActor [self] in try await finishRecording() }
        stopTask = task
        defer { stopTask = nil }
        return try await task.value
    }

    private func finishRecording() async throws -> URL {
        if !isRecording, let fileURL { return fileURL }
        guard isRecording, !isFinishing else { throw AudioError.busy }
        let id = generation
        isFinishing = true
        ticker?.cancel()
        ticker = nil
        defer { isFinishing = false }
        do {
            let url: URL
            if let microphone {
                microphone.stop()
                self.microphone = nil
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
                guard let microphoneURL else { throw AudioError.recording }
                url = microphoneURL
                self.microphoneURL = nil
            } else {
                guard let stream, let sink else { throw AudioError.recording }
                // If stop fails, keep the stream so Cancel can retry and keep the indicator visible.
                try await stream.stopCapture()
                try? stream.removeStreamOutput(sink, type: .audio)
                self.stream = nil
                self.sink = nil
                deactivatePicker()
                url = try await sink.finish(discard: false)
            }
            guard generation == id else {
                try? FileManager.default.removeItem(at: url)
                throw AudioError.cancelled
            }
            isRecording = false
            generation = UUID()
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0, size <= AudioTranscriptionClient.maximumBytes else {
                try? FileManager.default.removeItem(at: url)
                throw size == 0 ? AudioError.empty : .tooLarge
            }
            fileURL = url
            return url
        } catch {
            if stream == nil { isRecording = false }
            lastError = (error as? AudioError ?? .recording).localizedDescription
            throw error as? AudioError ?? AudioError.recording
        }
    }

    func cancel() async {
        if let cancelTask { await cancelTask.value; return }
        let task = Task { @MainActor [self] in await discardRecording() }
        cancelTask = task
        defer { cancelTask = nil }
        await task.value
    }

    private func discardRecording() async {
        generation = UUID()
        isFinishing = true
        defer { isFinishing = false }
        // Finish the in-flight start before stopping. A late system completion must
        // never reactivate capture after Cancel has already returned.
        let pendingStart = startTask
        pendingStart?.cancel()
        await pendingStart?.value
        startTask = nil
        if let stopTask { _ = try? await stopTask.value }
        isFinishing = true
        ticker?.cancel()
        ticker = nil
        finishStart(error: AudioError.cancelled)
        microphone?.stop()
        microphone = nil
        if let microphoneURL { try? FileManager.default.removeItem(at: microphoneURL) }
        microphoneURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if let stream {
            do { try await stream.stopCapture() }
            catch {
                // Never report stopped while the system still owns a live capture session.
                lastError = "停止录音未获系统确认，请重试取消或使用系统共享指示停止。"
                isRecording = true
                return
            }
            if let sink { try? stream.removeStreamOutput(sink, type: .audio) }
        }
        stream = nil
        if let sink { _ = try? await sink.finish(discard: true) }
        sink = nil
        deactivatePicker()
        isRecording = false
        clearFinishedFile()
    }

    private func beginTimer() {
        startedAt = Date()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                guard let self, self.isRecording, let startedAt = self.startedAt else { return }
                self.elapsedSeconds = Date().timeIntervalSince(startedAt)
                if self.elapsedSeconds >= Self.maximumDuration {
                    _ = try? await self.stop()
                    return
                }
            }
        }
    }

    private func finishStart(error: AudioError?, keepPicker: Bool = false) {
        isRequesting = false
        if !keepPicker { deactivatePicker() }
        let continuation = startContinuation
        startContinuation = nil
        if let error { continuation?.resume(throwing: error) }
        else { continuation?.resume() }
    }

    private func deactivatePicker() {
        if let observer { picker.remove(observer) }
        observer = nil
        picker.isActive = false
    }

    private func clearFinishedFile() {
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
        fileURL = nil
    }

    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("alwayside-audio-\(UUID().uuidString).m4a")
    }
}

extension AudioRecorder: SCStreamDelegate {
    nonisolated func stream(_ stream: SCStream, didStopWithError error: any Error) {
        let id = ObjectIdentifier(stream)
        Task { @MainActor [weak self] in
            guard let self, self.stream.map(ObjectIdentifier.init) == id, !self.isFinishing else { return }
            self.lastError = "系统音频收录已中断，请重新录制。"
            self.stream = nil
            await self.cancel()
        }
    }
}

extension AudioRecorder: AVAudioRecorderDelegate {
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        let id = ObjectIdentifier(recorder)
        Task { @MainActor [weak self] in
            guard let self, self.microphone.map(ObjectIdentifier.init) == id, !self.isFinishing else { return }
            if flag { _ = try? await self.stop() }
            else {
                self.lastError = AudioError.recording.localizedDescription
                await self.cancel()
            }
        }
    }
}
