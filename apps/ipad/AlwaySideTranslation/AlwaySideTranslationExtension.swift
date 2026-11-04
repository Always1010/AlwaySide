import SwiftUI
import TranslationUIProvider

@main
struct AlwaySideTranslationExtension: TranslationUIProviderExtension {
    var body: some TranslationUIProviderExtensionScene {
        TranslationUIProviderSelectedTextScene { context in
            TranslationCardView(context: context)
        }
    }
}
