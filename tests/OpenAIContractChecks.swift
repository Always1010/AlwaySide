import Foundation

// Compiled after the production Foundation-only sources in one temporary file.
// This lets the fixture exercise private stream code without widening the app API.
// URLProtocol owns every fixture request; no provider, credential or network is used.
private final class MemorySSEProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var payload = Data()
    private static var chunkSize = 4096

    static func install(_ bytes: Data, chunkSize: Int) {
        lock.lock()
        defer { lock.unlock() }
        payload = bytes
        self.chunkSize = chunkSize
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        let bytes = Self.payload
        let chunk = Self.chunkSize
        Self.lock.unlock()
        guard let url = request.url, url.absoluteString == "https://fixture.invalid/responses",
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                             headerFields: ["Content-Type": "text/event-stream"]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for offset in stride(from: 0, to: bytes.count, by: chunk) {
            client?.urlProtocol(self, didLoad: bytes.subdata(in: offset..<min(offset + chunk, bytes.count)))
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private extension VisionClient {
    func fixtureStream(_ bytes: Data, settings: OpenAISettings, chunkSize: Int = 4096) async throws -> ModelAnswer {
        MemorySSEProtocol.install(bytes, chunkSize: chunkSize)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MemorySSEProtocol.self]
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let (stream, _) = try await session.bytes(from: URL(string: "https://fixture.invalid/responses")!)
        return try await readStream(stream, key: "sk-FAKE-contract-only-1234567890", settings: settings, onEvent: nil)
    }

    func fixtureLiveText(_ text: String, key: String) -> String { streamingText(text, key: key) }

    func fixtureFinal(_ data: Data, settings: OpenAISettings, key: String) throws -> ModelAnswer {
        redacted(try parse(provider: .openAI, data: data, openAISettings: settings, redacting: key), key: key)
    }
}

@main
private struct OpenAIContractChecks {
    private static var checks = 0

    static func expect(_ condition: @autoclosure () -> Bool, _ label: String) {
        guard condition() else { fatalError("FAIL: \(label)") }
        checks += 1
    }

    static func json(_ object: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: object) }

    static func response(text: String = "解释画面", status: String = "completed",
                         searchStatus: String? = nil, annotations: [[String: Any]] = []) -> [String: Any] {
        var output: [[String: Any]] = [["type": "message", "content": [
            ["type": "output_text", "text": text, "annotations": annotations]
        ]]]
        if let searchStatus { output.insert(["type": "web_search_call", "status": searchStatus], at: 0) }
        return ["status": status, "output": output]
    }

    static func citation(_ url: String, title: String = "来源") -> [String: Any] {
        ["type": "url_citation", "url": url, "title": title]
    }

    static func main() async throws {
        let client = VisionClient()
        var settings = OpenAISettings.defaults
        for mode in OpenAISearchMode.allCases {
            settings.searchMode = mode
            let data = try client.makeBody(provider: .openAI, question: "解释画面", jpeg: Data([1]),
                                           history: [], openAISettings: settings, stream: true)
            let body = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            expect(body["tool_choice"] as? String == (mode == .off ? "none" : mode == .auto ? "auto" : "required"),
                   "search selection must reach the API")
            let tools = body["tools"] as? [[String: Any]]
            expect(mode == .off ? tools == nil : tools?.first?["type"] as? String == "web_search",
                   "only enabled search offers the real web tool")
            expect(body["store"] as? Bool == false && body["instructions"] as? String == settings.instructions,
                   "no server storage; user instructions are the effective instructions")
        }

        settings.searchMode = .required
        let absent = try client.parse(provider: .openAI, data: json(response()), openAISettings: settings)
        expect(!absent.complete && absent.searchState == .requiredNotPerformed, "required search cannot succeed without a tool call")
        let failed = try client.parse(provider: .openAI, data: json(response(searchStatus: "failed")), openAISettings: settings)
        expect(!failed.complete && failed.searchState == .failed, "failed search cannot enter completed history")
        let noSources = try client.parse(provider: .openAI, data: json(response(searchStatus: "completed")), openAISettings: settings)
        expect(noSources.searchState == .completedWithoutCitations, "tool completion without sources is labeled honestly")
        let cited = try client.parse(provider: .openAI, data: json(response(searchStatus: "completed", annotations: [
            citation("https://example.org/source"), citation("https://example.org/source"),
            citation("javascript:alert(1)"), citation("file:///private/test"), citation("https://user:pass@example.org/")
        ])), openAISettings: settings)
        expect(cited.complete && cited.searchState == .completed && cited.citations.count == 1,
               "sources are distinct HTTP(S) links without embedded credentials")
        settings.searchMode = .off
        do {
            _ = try client.parse(provider: .openAI, data: json(response(searchStatus: "completed")), openAISettings: settings)
            fatalError("FAIL: disabled search accepted an unexpected tool call")
        } catch ClientError.invalidResponse { checks += 1 }
        let incomplete = try client.parse(provider: .openAI, data: json(response(status: "incomplete")), openAISettings: settings)
        expect(!incomplete.complete, "incomplete final response cannot enter history")

        let key = "sk-FAKE-contract-only-1234567890"
        let prefix = String(repeating: "A", count: 11990)
        let redacted = try client.fixtureFinal(json(response(text: prefix + key + " end")), settings: settings, key: key)
        expect(redacted.text == String((prefix + "[已隐藏密钥] end").prefix(12000)), "redaction happens before visible truncation")
        let longAnswer = try client.fixtureFinal(json(response(text: String(repeating: "A", count: 12001))), settings: settings, key: key)
        expect(longAnswer.truncated && !longAnswer.complete && longAnswer.text.count == 12000, "truncated text is explicit and excluded from history")
        settings.searchMode = .auto
        let encodedKey = key.map { String(format: "%%%02X", $0.asciiValue!) }.joined()
        let privateSource = try client.fixtureFinal(json(response(searchStatus: "completed", annotations: [
            citation("https://example.org/?q=" + encodedKey)
        ])), settings: settings, key: key)
        expect(privateSource.citations.isEmpty && privateSource.searchState == .completedWithoutCitations,
               "percent-encoded fake credentials never become clickable sources")
        expect(client.fixtureLiveText("短回答", key: key) == "短回答", "short ordinary text streams without waiting for the final response")
        for length in 1..<key.count {
            let partial = "回答：" + String(key.prefix(length))
            expect(client.fixtureLiveText(partial, key: key) == "回答：", "a split fake key prefix is withheld")
            expect(!client.fixtureLiveText(partial + String(key.dropFirst(length)), key: key).contains(key), "a completed split fake key is redacted")
        }

        let completed = try json(["type": "response.completed", "response": response()])
        let payload = String(data: completed, encoding: .utf8)!
        let crlf = Data((": heartbeat\r\ndata: " + payload + "\r\n\r\n").utf8)
        let decoded = try await client.fixtureStream(crlf, settings: settings, chunkSize: 1)
        expect(decoded.complete && decoded.text == "解释画面", "actual SSE decoder handles split UTF-8, comments and CRLF")
        let eof = try await client.fixtureStream(Data(("data: " + payload).utf8), settings: settings)
        expect(eof.complete && eof.text == "解释画面", "actual SSE decoder flushes a final event at EOF")
        let disconnected = Data("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n".utf8)
        do {
            _ = try await client.fixtureStream(disconnected, settings: settings)
            fatalError("FAIL: disconnected SSE was promoted to a completed answer")
        } catch ClientError.invalidResponse { checks += 1 }
        do {
            _ = try await client.fixtureStream(Data(repeating: 65, count: 2 * 1024 * 1024 + 1), settings: settings)
            fatalError("FAIL: unterminated oversized SSE line accepted")
        } catch ClientError.invalidResponse { checks += 1 }
        var oversizedFrames = Data()
        let comment = Data((":" + String(repeating: "A", count: 4094) + "\n").utf8)
        for _ in 0...2048 { oversizedFrames.append(comment) }
        do {
            _ = try await client.fixtureStream(oversizedFrames, settings: settings)
            fatalError("FAIL: oversized SSE response accepted")
        } catch ClientError.invalidResponse { checks += 1 }
        print("PASS: \(checks) OpenAI contract assertions; actual Foundation-only production code; no network or credentials")
    }
}
