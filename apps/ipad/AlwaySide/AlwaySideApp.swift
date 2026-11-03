import SwiftUI
import UIKit

@main
struct AlwaySideApp: App {
    @StateObject private var model = CompanionModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CompanionView(model: model, capture: model.capture)
                .task {
                    if scenePhase == .active { await ScreenshotInboxDelivery.consume(using: model) }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        Task { await ScreenshotInboxDelivery.consume(using: model) }
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: ScreenshotInbox.didChange)) { _ in
                    if scenePhase == .active {
                        Task { await ScreenshotInboxDelivery.consume(using: model) }
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: ScreenshotInbox.willReceive)) { _ in
                    if scenePhase == .active { ScreenshotInboxDelivery.begin(using: model) }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIWindow.didBecomeKeyNotification)) { notification in
                    guard let window = notification.object as? UIWindow,
                          window.windowScene?.activationState == .foregroundActive else { return }
                    Task { await ScreenshotInboxDelivery.consume(using: model) }
                }
                .onOpenURL { url in
                    guard url.scheme?.lowercased() == "alwayside",
                          url.host?.lowercased() == "ask" else { return }
                    // Legacy link only opens the session. It cannot take a screenshot.
                    Task { await ScreenshotInboxDelivery.consume(using: model) }
                }
        }
    }
}
