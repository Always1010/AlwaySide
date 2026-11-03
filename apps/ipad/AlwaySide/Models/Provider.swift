import Foundation

enum ModelProvider: String, CaseIterable, Identifiable {
    case openAI
    case deepSeek

    var id: String { rawValue }
    var name: String { self == .openAI ? "OpenAI" : "DeepSeek" }
    var model: String { self == .openAI ? "gpt-6-luna" : "deepseek-flash" }
    var endpoint: URL {
        switch self {
        case .openAI: return URL(string: "https://api.openai.com/v1/responses")!
        case .deepSeek: return URL(string: "https://api.deepseek.com/chat/completions")!
        }
    }
}

struct ConversationTurn: Identifiable {
    let id = UUID()
    let question: String
    let answer: String
}

struct EvidenceSnapshot: Identifiable {
    let frame: CapturedFrame
    let origin: String
    let isImported: Bool
    var id: UUID { frame.id }
}

struct ModelAnswer {
    let text: String
    let complete: Bool
}

enum ClientError: Error, LocalizedError {
    case missingKey, keychain, invalidImage, tooLarge, unauthorized, rateLimited
    case timeout, network, server, invalidResponse, rejected, cancelled

    var errorDescription: String? {
        switch self {
        case .missingKey: return "请先在设置中保存所选供应商的 API Key。"
        case .keychain: return "无法访问本机钥匙串。请解锁设备后重试。"
        case .invalidImage: return "无法读取这张图片，请选择 JPEG 或 PNG 图片。"
        case .tooLarge: return "图片过大，请使用较小的截图（原文件不超过 20 MB）。"
        case .unauthorized: return "供应商拒绝了密钥或账户权限（401/403）。请在设置中检查。"
        case .rateLimited: return "供应商返回 429。请检查账户额度或稍后手动重试。"
        case .timeout: return "请求超时。可以重新提问；供应商可能已产生用量。"
        case .network: return "网络连接失败。不会自动重发，请检查网络后手动提问。"
        case .server: return "供应商服务暂时不可用。请稍后手动重试。"
        case .invalidResponse: return "未收到可识别的回答。不会自动重发。"
        case .rejected: return "供应商拒绝了请求，请检查图片、模型及账户权限。"
        case .cancelled: return "已取消。已发送的数据无法撤回，供应商可能已产生用量。"
        }
    }
}
