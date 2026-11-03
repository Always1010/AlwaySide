import SwiftUI

@main
struct AlwaySideApp: App {
    @StateObject private var model = CompanionModel()

    var body: some Scene {
        WindowGroup {
            CompanionView(model: model, capture: model.capture)
                .onOpenURL { url in
                    guard url.scheme?.lowercased() == "alwayside",
                          url.host?.lowercased() == "ask" else { return }
                    // Opening the existing session does not send a question or upload a frame.
                    // Window placement and Slide Over restoration remain system-controlled.
                    model.lockFrameBeforeInteraction()
                }
        }
    }
}
