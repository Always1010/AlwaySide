import SwiftUI

@main
struct AlwaySideApp: App {
    @StateObject private var model = CompanionModel()

    var body: some Scene {
        WindowGroup {
            CompanionView(model: model, capture: model.capture)
        }
    }
}
