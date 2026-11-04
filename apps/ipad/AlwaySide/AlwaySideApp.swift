import SwiftUI
import UIKit

@main
struct AlwaySideApp: App {
    @StateObject private var model = CompanionModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CompanionView(model: model, capture: model.capture)
                .handlesExternalEvents(preferring: [WindowRecovery.contentIdentifier], allowing: [WindowRecovery.contentIdentifier])
                .task {
                    refreshKeyStatusIfAvailable()
                    if scenePhase == .active {
                        await ScreenshotInboxDelivery.consume(using: model)
                        consumeQuickReadHandoff()
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        refreshKeyStatusIfAvailable()
                        Task {
                            await ScreenshotInboxDelivery.consume(using: model)
                            consumeQuickReadHandoff()
                        }
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                    refreshKeyStatusIfAvailable()
                }
                .onReceive(NotificationCenter.default.publisher(for: ScreenshotInbox.didChange)) { notification in
                    if let failure = notification.object as? ScreenshotInbox.ReceiptFailure {
                        ScreenshotInboxDelivery.fail(using: model, receiptID: failure.id, message: failure.message)
                    } else if scenePhase == .active {
                        Task { await ScreenshotInboxDelivery.consume(using: model) }
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: ScreenshotInbox.willReceive)) { notification in
                    if let receiptID = notification.object as? UUID {
                        ScreenshotInboxDelivery.begin(using: model, receiptID: receiptID)
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: QuickReadStore.handoffNotification)) { _ in
                    if scenePhase == .active { consumeQuickReadHandoff() }
                }
                .onReceive(NotificationCenter.default.publisher(for: ShortcutListening.didFinish)) { _ in
                    if let result = ShortcutListening.shared.takeHandoff() { model.acceptAudioRead(result) }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIWindow.didBecomeKeyNotification)) { notification in
                    guard let window = notification.object as? UIWindow,
                          window.windowScene?.activationState == .foregroundActive else { return }
                    Task {
                        await ScreenshotInboxDelivery.consume(using: model)
                        consumeQuickReadHandoff()
                    }
                }
                .onOpenURL { url in
                    guard url.scheme?.lowercased() == "alwayside",
                          url.host?.lowercased() == "ask" else { return }
                    // Legacy link only opens the session. It cannot take a screenshot.
                    Task { await ScreenshotInboxDelivery.consume(using: model) }
                }
        }.handlesExternalEvents(matching: [WindowRecovery.contentIdentifier])
    }

    @MainActor
    private func consumeQuickReadHandoff() {
        if let result = QuickReadStore.shared.takeHandoff() { model.acceptQuickRead(result) }
        if let result = ShortcutListening.shared.takeHandoff() { model.acceptAudioRead(result) }
    }

    @MainActor
    private func refreshKeyStatusIfAvailable() {
        // Locked/background launches retry once the scene is active and Keychain data is available.
        guard scenePhase == .active, UIApplication.shared.isProtectedDataAvailable else { return }
        model.refreshKeyStatus()
    }
}
