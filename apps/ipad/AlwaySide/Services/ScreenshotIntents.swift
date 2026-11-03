import AppIntents
import UniformTypeIdentifiers

/// Put the system Take Screenshot action before this action in Shortcuts.
/// Background mode deliberately does not activate or resize the assistant's window.
struct ReceiveScreenshotIntent: AppIntent {
    static let title: LocalizedStringResource = "接收截图（保持当前窗口）"
    static let description = IntentDescription("接收前一步系统截屏，保存在本机；不打开助手、不发送给模型。之后从边缘手势唤回已有小窗。")
    static let supportedModes: IntentModes = .background

    @Parameter(title: "截图", supportedContentTypes: [.image],
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var image: IntentFile

    static var parameterSummary: some ParameterSummary {
        Summary("接收 \(\.$image)，保持当前窗口")
    }

    func perform() async throws -> some IntentResult {
        try await ScreenshotInbox.shared.receive(image)
        return .result()
    }
}

/// Optional convenience; foreground activation can open the app full screen on iPadOS.
struct ReceiveScreenshotAndOpenIntent: AppIntent {
    static let title: LocalizedStringResource = "接收截图并打开助手"
    static let description = IntentDescription("接收前一步系统截屏并打开助手；iPadOS 可能打开全屏，不能保证恢复 Slide Over。不会自动发送问题。")
    static let supportedModes: IntentModes = .foreground(.immediate)

    @Parameter(title: "截图", supportedContentTypes: [.image],
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var image: IntentFile

    static var parameterSummary: some ParameterSummary {
        Summary("接收 \(\.$image) 并打开助手")
    }

    func perform() async throws -> some IntentResult {
        try await ScreenshotInbox.shared.receive(image)
        return .result()
    }
}
