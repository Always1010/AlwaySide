import Foundation

struct ChatSessionSummary: Codable, Identifiable {
    let id: UUID
    var title: String
    var updatedAt: Date
    var provider: String
    var preview: String
}

/// Only conversation content and the imported JPEG are stored here; credentials stay in Keychain.
@MainActor
final class ChatSessionStore {
    static let maximumSessions = 30
    static let maximumTurns = 60
    private let root: URL
    private(set) var summaries: [ChatSessionSummary] = []

    struct SavedTurn: Codable {
        var question: String
        var answer: String
        var sources: [SavedSource]
        var search: String
        var complete: Bool
    }
    struct SavedSource: Codable { var url: URL; var title: String }
    struct Session: Codable {
        var id: UUID
        var title: String
        var updatedAt: Date
        var provider: String
        var turns: [SavedTurn]
        var imageDate: Date?
        var imageWidth: Int?
        var imageHeight: Int?
        var imageOrigin: String?
        var audioTranscript: String?
    }

    enum StorageError: Error { case sessionLimit, turnLimit }

    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AlwaySide/Conversations", isDirectory: true)
        // Rebuild the small text index from atomic session documents. This recovers a save
        // that reached disk before an interrupted index write, without decoding any JPEG.
        if let directories = try? FileManager.default.contentsOfDirectory(at: self.root,
            includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) {
            summaries = directories.compactMap { directory in
                guard UUID(uuidString: directory.lastPathComponent) != nil,
                      let session = try? readSession(at: directory) else { return nil }
                return ChatSessionSummary(id: session.id, title: session.title, updatedAt: session.updatedAt,
                    provider: session.provider, preview: String((session.turns.last?.answer ?? "").prefix(100)))
            }.sorted { $0.updatedAt > $1.updatedAt }
        }
    }

    func load(_ id: UUID) throws -> (Session, EvidenceSnapshot?) {
        let directory = folder(id)
        let session = try readSession(at: directory)
        var snapshot: EvidenceSnapshot?
        if let width = session.imageWidth, let height = session.imageHeight, let date = session.imageDate {
            let jpeg = try Data(contentsOf: directory.appendingPathComponent("image.jpg"))
            guard jpeg.count <= 3 * 1024 * 1024 else { throw ClientError.invalidImage }
            let frame = CapturedFrame(id: UUID(), timestamp: date, monotonicSeconds: 0,
                                      jpegData: jpeg, width: width, height: height)
            snapshot = EvidenceSnapshot(frame: frame, origin: session.imageOrigin ?? "历史会话图片", isImported: true)
        }
        return (session, snapshot)
    }

    func save(id: UUID, provider: ModelProvider, snapshot: EvidenceSnapshot?, turns: [SavedTurn], audioTranscript: String? = nil) throws {
        guard !turns.isEmpty else { return }
        guard summaries.contains(where: { $0.id == id }) || summaries.count < Self.maximumSessions else {
            throw StorageError.sessionLimit
        }
        let directory = folder(id)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let imageURL = directory.appendingPathComponent("image.jpg")
        if let snapshot, !FileManager.default.fileExists(atPath: imageURL.path) {
            guard snapshot.frame.jpegData.count <= 3 * 1024 * 1024 else { throw ClientError.invalidImage }
            try snapshot.frame.jpegData.write(to: imageURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            excludeFromBackup(imageURL)
        }
        guard turns.count <= Self.maximumTurns else { throw StorageError.turnLimit }
        let recent = turns
        let title = String((turns.first?.question ?? "新对话").replacingOccurrences(of: "\n", with: " ").prefix(60))
        let session = Session(id: id, title: title, updatedAt: Date(), provider: provider.rawValue, turns: recent,
                              imageDate: snapshot?.frame.timestamp, imageWidth: snapshot?.frame.width,
                              imageHeight: snapshot?.frame.height, imageOrigin: snapshot?.origin, audioTranscript: audioTranscript)
        let document = try JSONEncoder().encode(session)
        guard document.count <= 8 * 1024 * 1024 else { throw ClientError.tooLarge }
        try document.write(to: directory.appendingPathComponent("conversation.json"),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        excludeFromBackup(directory)
        summaries.removeAll { $0.id == id }
        summaries.insert(ChatSessionSummary(id: id, title: title, updatedAt: session.updatedAt,
            provider: provider.rawValue, preview: String((recent.last?.answer ?? "").prefix(100))), at: 0)
        try saveIndex()
    }

    func delete(_ id: UUID) throws {
        // Remove the attachment and messages together, then update the local list.
        if FileManager.default.fileExists(atPath: folder(id).path) { try FileManager.default.removeItem(at: folder(id)) }
        summaries.removeAll { $0.id == id }
        try saveIndex()
    }

    func deleteAll() throws {
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
        summaries = []
    }

    func search(_ query: String) -> [ChatSessionSummary] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return summaries }
        return summaries.filter { summary in
            if summary.title.localizedStandardContains(term) { return true }
            guard let session = try? readSession(at: folder(summary.id)) else { return false }
            if session.audioTranscript?.localizedStandardContains(term) == true { return true }
            return session.turns.contains { $0.question.localizedStandardContains(term) || $0.answer.localizedStandardContains(term) }
        }
    }

    private func readSession(at directory: URL) throws -> Session {
        let url = directory.appendingPathComponent("conversation.json")
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        guard size <= 8 * 1024 * 1024 else { throw ClientError.invalidResponse }
        return try JSONDecoder().decode(Session.self, from: Data(contentsOf: url))
    }

    private func folder(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }
    private func saveIndex() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try JSONEncoder().encode(summaries).write(to: root.appendingPathComponent("index.json"),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        excludeFromBackup(root)
    }
    private func excludeFromBackup(_ url: URL) {
        var mutableURL = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? mutableURL.setResourceValues(values)
    }
}

extension ChatSessionStore.SavedTurn {
    init(_ turn: ConversationTurn, complete: Bool = true) {
        question = turn.question; answer = turn.answer; self.complete = complete
        sources = turn.citations.map { .init(url: $0.url, title: $0.title) }
        switch turn.searchState {
        case .notUsed: search = "notUsed"
        case .completed: search = "completed"
        case .completedWithoutCitations: search = "completedWithoutCitations"
        case .failed: search = "failed"
        case .requiredNotPerformed: search = "requiredNotPerformed"
        }
    }
    var turn: ConversationTurn {
        let state: AnswerSearchState
        switch search {
        case "completed": state = .completed
        case "completedWithoutCitations": state = .completedWithoutCitations
        case "failed": state = .failed
        case "requiredNotPerformed": state = .requiredNotPerformed
        default: state = .notUsed
        }
        return ConversationTurn(question: question, answer: answer,
            citations: sources.filter { isSafeWebURL($0.url) }.map { AnswerCitation(url: $0.url, title: $0.title) }, searchState: state)
    }
}

func isSafeWebURL(_ url: URL) -> Bool {
    ["https", "http"].contains(url.scheme?.lowercased() ?? "") &&
    !(url.host ?? "").isEmpty && url.user == nil && url.password == nil
}
