import Foundation

struct ChatSessionSummary: Codable, Identifiable, Equatable, Sendable {
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
    // Keep only searchable text, never attachments or source metadata. Normal stores
    // contain at most 30 documents of at most 8 MiB each. Older over-capacity stores
    // retain every summary, but their uncached documents are read on the search worker.
    private var searchDocuments: [UUID: SearchDocument] = [:]
    private var searchRevision: UInt64 = 0

    struct SavedTurn: Codable, Sendable {
        var question: String
        var answer: String
        var sources: [SavedSource]
        var search: String
        var complete: Bool
    }
    struct SavedSource: Codable, Sendable { var url: URL; var title: String }
    struct Session: Codable, Sendable {
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

    private struct SearchDocument: Sendable {
        let fields: [String]

        init(_ session: Session) {
            fields = [session.title, session.audioTranscript ?? ""] +
                session.turns.flatMap { [$0.question, $0.answer] }
        }

        func contains(_ term: String) -> Bool {
            fields.contains { !Task.isCancelled && $0.localizedStandardContains(term) }
        }
    }

    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AlwaySide/Conversations", isDirectory: true)
        // Atomic session documents are the source of truth; no second index write can
        // turn an already-persisted save or deletion into an apparent failure.
        if let directories = try? FileManager.default.contentsOfDirectory(at: self.root,
            includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) {
            summaries = directories.compactMap { directory in
                guard UUID(uuidString: directory.lastPathComponent) != nil,
                      let session = try? Self.readSession(at: directory) else { return nil }
                if searchDocuments.count < Self.maximumSessions {
                    searchDocuments[session.id] = SearchDocument(session)
                }
                return ChatSessionSummary(id: session.id, title: session.title, updatedAt: session.updatedAt,
                    provider: session.provider, preview: String((session.turns.last?.answer ?? "").prefix(100)))
            }.sorted { $0.updatedAt > $1.updatedAt }
        }
    }

    func load(_ id: UUID) throws -> (Session, EvidenceSnapshot?) {
        let directory = folder(id)
        let session = try Self.readSession(at: directory)
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
        excludeFromBackup(root)
        summaries.removeAll { $0.id == id }
        summaries.insert(ChatSessionSummary(id: id, title: title, updatedAt: session.updatedAt,
            provider: provider.rawValue, preview: String((recent.last?.answer ?? "").prefix(100))), at: 0)
        if searchDocuments[id] == nil, searchDocuments.count >= Self.maximumSessions,
           let evicted = summaries.last(where: { searchDocuments[$0.id] != nil }) {
            searchDocuments.removeValue(forKey: evicted.id)
        }
        searchDocuments[id] = SearchDocument(session)
        searchRevision &+= 1
    }

    func delete(_ id: UUID) throws {
        // Remove the attachment and messages together, then update the local list.
        if FileManager.default.fileExists(atPath: folder(id).path) { try FileManager.default.removeItem(at: folder(id)) }
        summaries.removeAll { $0.id == id }
        searchDocuments.removeValue(forKey: id)
        searchRevision &+= 1
    }

    func deleteAll() throws {
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
        summaries = []
        searchDocuments = [:]
        searchRevision &+= 1
    }

    func search(_ query: String) async -> [ChatSessionSummary] {
        guard !Task.isCancelled else { return [] }
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return summaries }
        while !Task.isCancelled {
            let revision = searchRevision
            let candidates = summaries
            let documents = searchDocuments
            let directory = root
            let worker = Task.detached(priority: .userInitiated) { () -> [ChatSessionSummary] in
                var matches: [ChatSessionSummary] = []
                for summary in candidates {
                    guard !Task.isCancelled else { return [] }
                    if summary.title.localizedStandardContains(term) {
                        matches.append(summary)
                        continue
                    }
                    let document: SearchDocument?
                    if let cached = documents[summary.id] {
                        document = cached
                    } else {
                        // Compatibility for a pre-existing store exceeding today's cap.
                        // Disk access and decoding stay entirely off the main actor.
                        let folder = directory.appendingPathComponent(summary.id.uuidString, isDirectory: true)
                        document = (try? Self.readSession(at: folder)).map { SearchDocument($0) }
                    }
                    if document?.contains(term) == true { matches.append(summary) }
                }
                return matches
            }
            let matches = await withTaskCancellationHandler(operation: {
                await worker.value
            }, onCancel: {
                worker.cancel()
            })
            guard !Task.isCancelled else { return [] }
            // A save/delete may have happened while the worker was suspended. Search
            // the new snapshot before publishing any stale or deleted result.
            if revision == searchRevision { return matches }
        }
        return []
    }

    private nonisolated static func readSession(at directory: URL) throws -> Session {
        let url = directory.appendingPathComponent("conversation.json")
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        guard size <= 8 * 1024 * 1024 else { throw ClientError.invalidResponse }
        return try JSONDecoder().decode(Session.self, from: Data(contentsOf: url))
    }

    private func folder(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }
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
