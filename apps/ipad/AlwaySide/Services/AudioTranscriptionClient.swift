import Foundation

enum AudioError: Error, LocalizedError {
    case permission, unavailable, busy, cancelled, recording, empty, tooLarge, unsupported
    case unauthorized, rateLimited, timeout, network, rejected, invalidResponse

    var errorDescription: String? {
        switch self {
        case .permission: return "未获得麦克风权限，请在系统设置中允许录音，或使用设备音频。"
        case .unavailable: return "无法启动设备音频，请重新选择系统共享来源，或导入音频文件。"
        case .busy: return "正在处理上一次录音，请稍后重试。"
        case .cancelled: return "已取消音频操作。"
        case .recording: return "录音未能完成，请重新录制。"
        case .empty: return "没有收到可用音频。请确认所选来源正在播放且允许录音，然后重试。"
        case .tooLarge: return "音频文件应小于 20 MB，请缩短后重试。"
        case .unsupported: return "请选择 M4A、MP3、WAV、MP4、MPEG、MPGA 或 WebM 音频文件。"
        case .unauthorized: return "OpenAI 拒绝了密钥或账户权限，请在设置中检查。"
        case .rateLimited: return "OpenAI 返回 429，请检查额度或稍后手动重试。"
        case .timeout: return "音频转写超时，可以手动重试；供应商可能已产生用量。"
        case .network: return "音频转写连接失败，请检查网络后手动重试。"
        case .rejected: return "OpenAI 拒绝了音频转写，请检查文件及账户是否可用 gpt-transcribe。"
        case .invalidResponse: return "未收到有效转写，未自动重试。"
        }
    }
}

private final class AudioRejectRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// Only called by an explicit transcription action. Recording never starts a request.
struct AudioTranscriptionClient {
    static let maximumBytes = 20 * 1024 * 1024
    static let model = "gpt-transcribe"
    private static let mimeTypes = ["m4a": "audio/mp4", "mp4": "audio/mp4", "mp3": "audio/mpeg",
                                    "mpeg": "audio/mpeg", "mpga": "audio/mpeg", "wav": "audio/wav",
                                    "webm": "audio/webm"]

    func makeRequest(fileURL: URL, key: String, boundary: String = UUID().uuidString) throws -> URLRequest {
        guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ClientError.missingKey }
        guard fileURL.isFileURL, let mime = Self.mimeTypes[fileURL.pathExtension.lowercased()] else {
            throw AudioError.unsupported
        }
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0 else { throw AudioError.empty }
        guard size <= Self.maximumBytes else { throw AudioError.tooLarge }
        let audio = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        guard !audio.isEmpty, audio.count <= Self.maximumBytes else { throw AudioError.tooLarge }
        var body = Data()
        func append(_ text: String) { body.append(Data(text.utf8)) }
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n\(Self.model)\r\n")
        // Use a fixed filename so private file paths and filenames never leave the device.
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.\(fileURL.pathExtension.lowercased())\"\r\nContent-Type: \(mime)\r\n\r\n")
        body.append(audio)
        append("\r\n--\(boundary)--\r\n")
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/audio/transcriptions")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        return request
    }

    func parse(_ data: Data, redacting key: String) throws -> String {
        guard data.count <= 1024 * 1024,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = object["text"] as? String else { throw AudioError.invalidResponse }
        let clean = raw.replacingOccurrences(of: key, with: "[已隐藏密钥]")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { throw AudioError.empty }
        guard clean.count <= 20000 else { throw AudioError.invalidResponse }
        return clean
    }

    func transcribe(fileURL: URL, key: String) async throws -> String {
        try Task.checkCancellation()
        let scoped = fileURL.startAccessingSecurityScopedResource()
        defer { if scoped { fileURL.stopAccessingSecurityScopedResource() } }
        let request: URLRequest
        do { request = try makeRequest(fileURL: fileURL, key: key) }
        catch let error as AudioError { throw error }
        catch let error as ClientError { throw error }
        catch { throw AudioError.unsupported }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 180
        config.waitsForConnectivity = false
        config.urlCache = nil
        config.httpCookieStorage = nil
        let session = URLSession(configuration: config, delegate: AudioRejectRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            return try await withTaskCancellationHandler {
                let (data, response) = try await session.data(for: request)
                try Task.checkCancellation()
                guard let http = response as? HTTPURLResponse else { throw AudioError.invalidResponse }
                switch http.statusCode {
                case 200..<300: return try parse(data, redacting: key)
                case 401, 403: throw AudioError.unauthorized
                case 429: throw AudioError.rateLimited
                default: throw AudioError.rejected
                }
            } onCancel: { session.invalidateAndCancel() }
        } catch is CancellationError { throw AudioError.cancelled }
        catch let error as URLError {
            if error.code == .cancelled { throw AudioError.cancelled }
            if error.code == .timedOut { throw AudioError.timeout }
            throw AudioError.network
        } catch let error as AudioError { throw error }
        catch { throw AudioError.invalidResponse }
    }
}
