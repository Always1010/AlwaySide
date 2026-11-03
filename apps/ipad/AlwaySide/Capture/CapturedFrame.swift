import Foundation

/// An in-memory image. Its times describe receipt, not an authenticated source App.
struct CapturedFrame: Identifiable, Sendable {
    let id: UUID
    let timestamp: Date
    let monotonicSeconds: TimeInterval
    let jpegData: Data
    let width: Int
    let height: Int
}

/// Bounds JPEG retention by both count and bytes. Data copies share immutable storage.
struct RecentFrameBuffer {
    static let maximumCount = 30
    static let maximumJPEGBytes = 24 * 1024 * 1024
    private(set) var frames: [CapturedFrame] = []
    private(set) var jpegBytes = 0

    mutating func append(_ frame: CapturedFrame) {
        guard !frame.jpegData.isEmpty, frame.jpegData.count <= Self.maximumJPEGBytes else { return }
        frames.append(frame)
        jpegBytes += frame.jpegData.count
        while frames.count > Self.maximumCount || jpegBytes > Self.maximumJPEGBytes {
            jpegBytes -= frames.removeFirst().jpegData.count
        }
    }

    mutating func removeAll() {
        frames.removeAll(keepingCapacity: false)
        jpegBytes = 0
    }
}

enum CaptureState: String, Sendable {
    case idle, requesting, starting, capturing, stopped, failed

    var title: String {
        switch self {
        case .idle: "尚未共享"
        case .requesting: "等待系统授权"
        case .starting: "正在启动共享"
        case .capturing: "正在共享画面"
        case .stopped: "已停止共享"
        case .failed: "共享未成功"
        }
    }
}
