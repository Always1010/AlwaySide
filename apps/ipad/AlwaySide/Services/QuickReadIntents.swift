import AppIntents
import SwiftUI
import UniformTypeIdentifiers

/// A network-free entry for checking the actual system surface before enabling image uploads.
struct PreviewQuickReadCardIntent: AppIntent {
    static let title: LocalizedStringResource = "体验原地卡片（不联网）"
    static let description = IntentDescription("显示一张示例阅读卡片，不截图、不读取密钥、不联网。划走后继续使用原来的 App。")
    static let supportedModes: IntentModes = .background

    func perform() async throws -> some IntentResult & ShowsSnippetIntent {
        return .result(snippetIntent: QuickReadSnippetIntent(requestID: "preview"))
    }
}

/// Running this clearly named action is the upload gesture. The old receive-only actions stay local.
struct ReadScreenshotInPlaceIntent: AppIntent, ProgressReportingIntent {
    static let title: LocalizedStringResource = "读懂截图（上传并原地显示）"
    static let description = IntentDescription("将前一步系统截屏上传至设置中选定的供应商，翻译并简要解释，在系统卡片显示结果；不打开助手。每次运行可能产生 API 用量，模型请求最多等待 25 秒，不自动重试。")
    static let supportedModes: IntentModes = .background

    @Parameter(title: "截图", supportedContentTypes: [.image],
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var image: IntentFile

    static var parameterSummary: some ParameterSummary {
        Summary("上传并读懂 \(\.$image)，原地显示")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ShowsSnippetIntent {
        let store = QuickReadStore.shared
        let id = store.begin()
        // Only access the system's progress object within perform().
        let executionProgress = progress
        executionProgress.totalUnitCount = 3
        executionProgress.localizedDescription = "正在读取截图"
        do {
            let provider = ModelProvider(rawValue: UserDefaults.standard.string(forKey: "provider") ?? "") ?? .openAI
            let settings: OpenAISettings
            if let data = UserDefaults.standard.data(forKey: "openAISettings") {
                guard let saved = try? JSONDecoder().decode(OpenAISettings.self, from: data) else {
                    throw ClientError.invalidSettings
                }
                settings = saved.migratingShippedDefaults()
            } else { settings = .defaults }
            if provider == .openAI, settings.validationMessage != nil { throw ClientError.invalidSettings }
            guard let key = try CredentialStore().read(provider), !key.isEmpty else { throw ClientError.missingKey }
            let snapshot = try await ScreenshotFileImport.load(image, receivedAt: Date())
            try Task.checkCancellation()
            guard store.isCurrent(id) else { throw CancellationError() }
            executionProgress.completedUnitCount = 1
            executionProgress.localizedDescription = "正在翻译并解释"
            let question = QuickReadStore.question
            try await store.run(id: id, snapshot: snapshot, provider: provider) {
                try await VisionClient().answer(provider: provider, key: key, question: question,
                    snapshot: snapshot, history: [], openAISettings: settings)
            }
        } catch is CancellationError {
            store.fail(id, message: "本次阅读已取消。")
            throw CancellationError()
        } catch {
            // Cancellation from URLSession is normalized by VisionClient; do not show a new card.
            if Task.isCancelled || !store.isCurrent(id) {
                store.fail(id, message: "本次阅读已取消。")
                throw CancellationError()
            }
            let message: String
            if let client = error as? ClientError {
                if case .timeout = client {
                    message = "本次阅读超过 25 秒，已停止等待。可回到原页面重新长按；不会自动重试，供应商可能已产生用量。"
                } else { message = client.localizedDescription }
            } else if let imageError = error as? ScreenshotHandoffError {
                message = imageError.localizedDescription
            } else {
                message = "无法读取本次截图或访问密钥，请检查快捷指令的截图变量及 App 设置。"
            }
            store.fail(id, message: message)
        }
        try Task.checkCancellation()
        guard store.isCurrent(id) else { throw CancellationError() }
        executionProgress.completedUnitCount = 3
        return .result(snippetIntent: QuickReadSnippetIntent(requestID: id.uuidString))
    }
}

/// Read-only: the system can perform this repeatedly, including when a card expands.
struct QuickReadSnippetIntent: SnippetIntent {
    static let title: LocalizedStringResource = "阅读结果卡片"
    static let isDiscoverable = false
    static let supportedModes: IntentModes = .background

    @Parameter(title: "阅读记录") var requestID: String
    @Parameter(title: "展开") var expanded: Bool

    init() {}
    init(requestID: String, expanded: Bool = false) {
        self.requestID = requestID
        self.expanded = expanded
    }

    @MainActor
    func perform() async throws -> some IntentResult & ShowsSnippetView {
        let id = UUID(uuidString: requestID)
        let result = id.flatMap { QuickReadStore.shared.result(for: $0) }
        let failure = id.flatMap { QuickReadStore.shared.failure(for: $0) }
        return .result(view: QuickReadCardView(requestID: requestID, result: result,
            failure: failure, expanded: expanded, isPreview: requestID == "preview"))
    }
}

struct ExpandQuickReadCardIntent: AppIntent {
    static let title: LocalizedStringResource = "展开阅读卡片"
    static let isDiscoverable = false
    static let supportedModes: IntentModes = .background
    @Parameter(title: "阅读记录") var requestID: String
    @Parameter(title: "展开") var expanded: Bool

    init() {}
    init(requestID: String, expanded: Bool) {
        self.requestID = requestID
        self.expanded = expanded
    }

    func perform() async throws -> some IntentResult & ShowsSnippetIntent {
        return .result(snippetIntent: QuickReadSnippetIntent(requestID: requestID, expanded: expanded))
    }
}

/// This is the only card action that requests foreground activation, by an explicit button tap.
struct ContinueQuickReadInAppIntent: AppIntent {
    static let title: LocalizedStringResource = "在 App 中追问"
    static let isDiscoverable = false
    static let supportedModes: IntentModes = .foreground(.immediate)
    @Parameter(title: "阅读记录") var requestID: String

    init() {}
    init(requestID: String) { self.requestID = requestID }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let id = UUID(uuidString: requestID) else { throw ScreenshotHandoffError.expired }
        try QuickReadStore.shared.prepareHandoff(id)
        return .result()
    }
}
