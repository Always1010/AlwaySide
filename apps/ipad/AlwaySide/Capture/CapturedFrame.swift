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
