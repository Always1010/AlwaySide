import AppIntents
import Combine
import UIKit

/// Requests the user's existing window, never a replacement scene or an Open App fallback.
@MainActor
final class WindowRecovery: ObservableObject {
    static let shared = WindowRecovery()
    nonisolated static let contentIdentifier = "alwayside.companion"
    @Published private(set) var status: String?
    private let defaultsKey = "companionWindowSessionID"

    func register(_ window: UIWindow) {
        guard let scene = window.windowScene else { return }
        if let existing = UserDefaults.standard.string(forKey: defaultsKey),
           UIApplication.shared.openSessions.contains(where: { $0.persistentIdentifier == existing }) { return }
        UserDefaults.standard.set(scene.session.persistentIdentifier, forKey: defaultsKey)
    }

    func dismissStatus() { status = nil }

    func requestExistingWindow() throws {
        guard let id = UserDefaults.standard.string(forKey: defaultsKey),
              let session = UIApplication.shared.openSessions.first(where: { $0.persistentIdentifier == id }) else {
            throw RecoveryError.missingWindow
        }
        status = "已请求恢复原窗口；请核对是否保持 Slide Over。"
        UIApplication.shared.requestSceneSessionActivation(session, userActivity: nil, options: nil) { [weak self] _ in
            Task { @MainActor in self?.status = "系统未能恢复原窗口，请从屏幕边缘手动滑回。" }
        }
    }

    enum RecoveryError: LocalizedError {
        case missingWindow
        var errorDescription: String? {
            "未找到原窗口。请先打开 AlwaySide，手动设为 Slide Over 并收至边缘，再运行此动作。"
        }
    }
}

struct RestoreCompanionWindowIntent: AppIntent, TargetContentProvidingIntent {
    static let title: LocalizedStringResource = "试验恢复原窗口（不联网）"
    static let description = IntentDescription("请求恢复之前的 AlwaySide 窗口，不录音、不上传、不新建窗口；系统决定能否保持 Slide Over。")
    static let supportedModes: IntentModes = .foreground(.dynamic)
    var contentIdentifier: String { WindowRecovery.contentIdentifier }

    @MainActor
    func perform() async throws -> some IntentResult {
        try WindowRecovery.shared.requestExistingWindow()
        return .result()
    }
}
