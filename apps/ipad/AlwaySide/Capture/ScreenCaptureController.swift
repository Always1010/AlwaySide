import Combine
import Foundation
@preconcurrency import ScreenCaptureKit

/// System picker events may arrive off the main actor. The filter is handed to
/// the main actor once and is never mutated by this App; no media buffer is wrapped.
private struct PickerSelection: @unchecked Sendable {
    let filter: SCContentFilter
    let streamID: ObjectIdentifier?
}

/// A fresh observer per presentation makes queued callbacks from an old picker
/// distinguishable after the user stops and starts another sharing session.
private final class CapturePickerObserver: NSObject, SCContentSharingPickerObserver {
    let onUpdate: @Sendable (PickerSelection) -> Void
    let onCancel: @Sendable () -> Void
    let onFailure: @Sendable () -> Void

    init(onUpdate: @escaping @Sendable (PickerSelection) -> Void,
         onCancel: @escaping @Sendable () -> Void,
         onFailure: @escaping @Sendable () -> Void) {
        self.onUpdate = onUpdate
        self.onCancel = onCancel
        self.onFailure = onFailure
        super.init()
    }

    func contentSharingPicker(_ picker: SCContentSharingPicker,
        didUpdateWith filter: SCContentFilter, for stream: SCStream?) {
        onUpdate(PickerSelection(filter: filter, streamID: stream.map(ObjectIdentifier.init)))
    }

    func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
        onCancel()
    }

    func contentSharingPickerStartDidFailWithError(_ error: any Error) { onFailure() }
}

@MainActor
final class ScreenCaptureController: NSObject, ObservableObject {
    @Published private(set) var state: CaptureState = .idle
    @Published private(set) var latestFrame: CapturedFrame?
    @Published private(set) var lastError: String?

    private let picker = SCContentSharingPicker.shared
    private let sampleQueue = DispatchQueue(label: "local.alwayside.screen-frames", qos: .utility)
    private var pickerObserver: CapturePickerObserver?
    private var pickerID: UUID?
    private var stream: SCStream?
    private var sink: ScreenFrameSink?
    private var recentFrames = RecentFrameBuffer()
    private var discardedBefore: TimeInterval = -.infinity
    private var generation: UInt64 = 0
    private var startTask: Task<Void, Never>?
    private var frameTask: Task<Void, Never>?
    private var isStopping = false

    func presentPicker() {
        guard !isStopping, state != .requesting, state != .starting, state != .capturing else { return }
        guard stream == nil else {
            lastError = "请先停止上一次共享，并检查系统屏幕共享指示，再重新开始。"
            return
        }
        clearFrames()
        lastError = nil
        state = .requesting
        var configuration = SCContentSharingPickerConfiguration()
        configuration.showsMicrophoneControl = false
        configuration.showsCameraControl = false
        picker.defaultConfiguration = configuration
        let id = UUID()
        pickerID = id
        let observer = CapturePickerObserver(onUpdate: { [weak self] selection in
            Task { @MainActor [weak self] in
                guard let self, self.pickerID == id else { return }
                self.beginCapture(selection)
            }
        }, onCancel: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.pickerID == id, self.state == .requesting else { return }
                self.clearFrames()
                self.deactivatePicker()
                self.state = .idle
            }
        }, onFailure: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.pickerID == id, self.state == .requesting else { return }
                self.fail("无法打开系统屏幕共享选择器，请稍后重试。")
            }
        })
        pickerObserver = observer
        picker.add(observer)
        picker.isActive = true
        picker.present()
    }

    func stopCapture() async {
        guard !isStopping else { return }
        isStopping = true
        defer { isStopping = false }
        generation &+= 1
        deactivatePicker()
        clearFrames()
        lastError = nil
        state = .stopped
        // Invalidate first, then wait for a pending start before stopping its stream.
        // This prevents startCapture's completion from reviving a stopped session.
        await startTask?.value
        startTask = nil
        do { try await releaseStream() }
        catch {
            lastError = "系统未确认共享已停止，请同时检查系统的屏幕共享指示。"
            state = .failed
        }
    }

    /// Receipt-time heuristic only: this does not identify the source application.
    /// Never fall back to a newer live frame, which may already contain this window.
    func frameBeforeInteraction(at boundary: TimeInterval, after lowerBound: TimeInterval = -.infinity) -> CapturedFrame? {
        guard state == .capturing else { return nil }
        let cutoff = boundary - 1.2
        return recentFrames.frames.last {
            $0.monotonicSeconds >= lowerBound && $0.monotonicSeconds <= cutoff &&
            boundary - $0.monotonicSeconds <= 12
        }
    }

    /// Clear retained evidence while allowing a running stream to deliver new frames.
    func discardRecentFrames() {
        discardedBefore = ProcessInfo.processInfo.systemUptime
        recentFrames.removeAll()
        latestFrame = nil
    }

    private func beginCapture(_ selection: PickerSelection) {
        guard !isStopping else { return }
        if let streamID = selection.streamID {
            guard let stream, ObjectIdentifier(stream) == streamID else { return }
        } else {
            guard state == .requesting else { return }
        }
        generation &+= 1
        let token = generation
        clearFrames()
        state = .starting
        lastError = nil
        let previousStart = startTask
        startTask = Task { [weak self] in
            await previousStart?.value
            guard let self, self.generation == token else { return }
            await self.startStream(filter: selection.filter, token: token)
        }
    }

    private func startStream(filter: SCContentFilter, token: UInt64) async {
        do {
            try await releaseStream()
            guard generation == token else { return }
            let configuration = SCStreamConfiguration()
            let size = filter.contentRect.size
            guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0 else {
                fail("系统没有提供有效的共享画面尺寸。")
                return
            }
            let scale = min(1, CGFloat(ScreenFrameSink.maximumDimension) / max(size.width, size.height))
            configuration.width = max(1, Int(floor(size.width * scale)))
            configuration.height = max(1, Int(floor(size.height * scale)))
            configuration.capturesAudio = false
            // iOS controls the source cadence and queue. ScreenFrameSink throttles
            // JPEG processing locally; only the screen output is attached below.

            let (frames, continuation) = AsyncStream<CapturedFrame>.makeStream(bufferingPolicy: .bufferingNewest(1))
            let output = ScreenFrameSink(continuation: continuation)
            let newStream = SCStream(filter: filter, configuration: configuration, delegate: self)
            try newStream.addStreamOutput(output, type: .screen, sampleHandlerQueue: sampleQueue)
            stream = newStream
            sink = output
            frameTask = Task { [weak self] in
                for await frame in frames {
                    guard !Task.isCancelled else { return }
                    self?.accept(frame, token: token)
                }
            }
            try await newStream.startCapture()
            guard generation == token else { return }
            state = .capturing
        } catch {
            guard generation == token else { return }
            // A failed start may have partially allocated system resources.
            try? await releaseStream()
            guard generation == token else { return }
            fail("无法开始屏幕共享。请通过系统选择器重新授权；若反复失败，请检查系统共享状态。")
        }
    }

    private func accept(_ frame: CapturedFrame, token: UInt64) {
        guard generation == token, state == .starting || state == .capturing,
              frame.monotonicSeconds >= discardedBefore else { return }
        recentFrames.append(frame)
        latestFrame = frame
    }

    private func clearFrames() {
        frameTask?.cancel()
        frameTask = nil
        sink?.finish()
        discardRecentFrames()
    }

    private func releaseStream() async throws {
        let oldStream = stream
        let oldSink = sink
        stream = nil
        sink = nil
        oldSink?.finish()
        frameTask?.cancel()
        frameTask = nil
        guard let oldStream else { return }
        defer {
            if let oldSink { try? oldStream.removeStreamOutput(oldSink, type: .screen) }
        }
        do { try await oldStream.stopCapture() }
        catch {
            // Keep a handle for a manual retry if the system did not confirm stop.
            // Output has already been disconnected and its bounded channel closed.
            stream = oldStream
            sink = oldSink
            throw error
        }
    }

    private func deactivatePicker() {
        pickerID = nil
        picker.isActive = false
        if let pickerObserver {
            picker.remove(pickerObserver)
            self.pickerObserver = nil
        }
    }

    private func fail(_ message: String) {
        generation &+= 1
        clearFrames()
        deactivatePicker()
        lastError = message
        state = .failed
    }

    private func didStop(streamID: ObjectIdentifier, userStopped: Bool) {
        guard let stream, ObjectIdentifier(stream) == streamID else { return }
        generation &+= 1
        self.stream = nil
        clearFrames()
        sink = nil
        deactivatePicker()
        lastError = userStopped ? nil : "屏幕共享已中断，缓存已清空。请重新开始共享。"
        state = userStopped ? .stopped : .failed
    }
}

extension ScreenCaptureController: SCStreamDelegate {
    nonisolated func stream(_ stream: SCStream, didStopWithError error: any Error) {
        let streamID = ObjectIdentifier(stream)
        let systemError = error as NSError
        let userStopped = systemError.domain == SCStreamErrorDomain &&
            systemError.code == SCStreamError.Code.userStopped.rawValue
        Task { @MainActor [weak self] in self?.didStop(streamID: streamID, userStopped: userStopped) }
    }
}
