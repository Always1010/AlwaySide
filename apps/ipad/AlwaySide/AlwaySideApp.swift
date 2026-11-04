import SwiftUI
import UIKit

@main
struct AlwaySideApp: App {
    var body: some Scene {
        WindowGroup { CompanionWindow() }
    }
}

/// Every manually placed window owns its conversation and ongoing operations.
private struct CompanionWindow: View {
    @StateObject private var model = CompanionModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        CompanionView(model: model, capture: model.capture, listening: model.listening)
            .task { refreshKeyStatus() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { refreshKeyStatus() }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                refreshKeyStatus()
            }
    }

    private func refreshKeyStatus() {
        guard scenePhase == .active, UIApplication.shared.isProtectedDataAvailable else { return }
        model.refreshKeyStatus()
        model.syncTranslationSettings()
    }
}
