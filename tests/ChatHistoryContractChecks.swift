import Foundation

/// Exercises the production file store without networking, UIKit, credentials, or device automation.
@MainActor
@main
private struct ChatHistoryContractChecks {
    private static var checks = 0
    private static func expect(_ value: @autoclosure () -> Bool, _ label: String) {
        guard value() else { fatalError("FAIL: \(label)") }
        checks += 1
    }

    static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("alwayside-history-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ChatSessionStore(root: root)
        let id = UUID()
        let image = Data([0xFF, 0xD8, 0xFF, 0xD9])
        let frame = CapturedFrame(id: UUID(), timestamp: Date(timeIntervalSince1970: 1700000000),
            monotonicSeconds: 0, jpegData: image, width: 2, height: 3)
        let snapshot = EvidenceSnapshot(frame: frame, origin: "fixture import", isImported: true)
        let source = AnswerCitation(url: URL(string: "https://example.com/evidence")!, title: "来源")
        let answer = String(repeating: "开头内容", count: 50) + "全文检索标记"
        let turn = ConversationTurn(question: "翻译这张图", answer: answer,
            citations: [source], searchState: .completed)
        let transcript = String(repeating: "audio text ", count: 500) + " CAFÉ"
        let saved = ChatSessionStore.SavedTurn(turn)
        try store.save(id: id, provider: .openAI, snapshot: snapshot, turns: [saved], audioTranscript: transcript)
        expect(store.summaries.count == 1, "saved session listed")
        expect(!store.summaries[0].preview.contains("全文检索标记"), "search fixture beyond preview")
        let answerMatches = await store.search("全文检索标记")
        expect(answerMatches.first?.id == id, "search full answer")
        let transcriptMatches = await store.search("audio text")
        expect(transcriptMatches.first?.id == id, "search complete attached transcript")
        let normalizedMatches = await store.search("  cafe\n")
        expect(normalizedMatches.first?.id == id, "search retains localized case and diacritic matching")
        let allMatches = await store.search(" \n ")
        expect(allMatches == store.summaries, "blank query retains summary order")

        // Repeated searches use the text snapshot, even if the document temporarily
        // cannot be read. Images and previews do not supply this answer-only marker.
        let documentURL = root.appendingPathComponent("\(id.uuidString)/conversation.json")
        let heldDocumentURL = root.appendingPathComponent("held-conversation.json")
        try FileManager.default.moveItem(at: documentURL, to: heldDocumentURL)
        let cachedMatches = await store.search("全文检索标记")
        expect(cachedMatches.first?.id == id, "search does not reread conversation on each query")
        try FileManager.default.moveItem(at: heldDocumentURL, to: documentURL)

        expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("index.json").path), "save needs no redundant index")
        // A legacy index path that cannot be overwritten must not make a successful
        // document save or directory deletion appear to fail.
        try FileManager.default.createDirectory(at: root.appendingPathComponent("index.json"), withIntermediateDirectories: false)
        try Data("legacy blocker".utf8).write(to: root.appendingPathComponent("index.json/keep"))
        let reopened = ChatSessionStore(root: root)
        let (session, attachment) = try reopened.load(id)
        expect(reopened.summaries.count == 1, "rebuild from documents despite blocked legacy index")
        expect(session.turns[0].answer == answer, "complete answer persisted")
        expect(session.audioTranscript == transcript, "long transcript round trip without question truncation")
        expect(session.turns[0].turn.citations == [source], "sources persisted")
        expect(session.turns[0].turn.searchState == .completed, "search state persisted")
        expect(attachment?.frame.jpegData == image && attachment?.frame.width == 2, "image bytes and dimensions persisted")
        expect(attachment?.frame.timestamp == frame.timestamp, "original image receipt date persisted")
        let interrupted = ChatSessionStore.SavedTurn(turn, complete: false)
        try reopened.save(id: id, provider: .openAI, snapshot: snapshot, turns: [saved, interrupted], audioTranscript: transcript)
        let afterPartial = try reopened.load(id).0
        expect(afterPartial.turns.count == 2 && !afterPartial.turns[1].complete, "interrupted output preserved distinctly")
        expect(ChatSessionStore(root: root).summaries.count == 1, "save succeeds despite blocked legacy index")
        var edited = saved
        edited.answer = "替换后的答案标记"
        var editedInterrupted = interrupted
        editedInterrupted.answer = edited.answer
        try reopened.save(id: id, provider: .openAI, snapshot: snapshot, turns: [edited, editedInterrupted], audioTranscript: transcript)
        let editedMatches = await reopened.search("替换后的答案标记")
        expect(editedMatches.first?.id == id, "save immediately refreshes searchable text")
        let staleMatches = await reopened.search("全文检索标记")
        expect(staleMatches.isEmpty, "save invalidates the previous searchable version")
        let cancelled = Task { await reopened.search("替换后的答案标记") }
        cancelled.cancel()
        let cancelledMatches = await cancelled.value
        expect(cancelledMatches.isEmpty, "cancelled search discards results")

        do {
            try reopened.save(id: id, provider: .openAI, snapshot: snapshot,
                turns: Array(repeating: saved, count: ChatSessionStore.maximumTurns + 1))
            fatalError("FAIL: turn limit accepted")
        } catch ChatSessionStore.StorageError.turnLimit { checks += 1 }
        expect(tryCount(reopened, id: id) == 2, "turn limit retains previous file")

        for _ in 1..<ChatSessionStore.maximumSessions {
            try reopened.save(id: UUID(), provider: .openAI, snapshot: nil, turns: [saved])
        }
        do {
            try reopened.save(id: UUID(), provider: .openAI, snapshot: nil, turns: [saved])
            fatalError("FAIL: session limit accepted")
        } catch ChatSessionStore.StorageError.sessionLimit { checks += 1 }
        expect(reopened.summaries.count == ChatSessionStore.maximumSessions, "session cap does not evict")
        expect(tryCount(reopened, id: id) == 2, "oldest content retained at capacity")
        let failedSaveMatches = await reopened.search("替换后的答案标记")
        expect(failedSaveMatches.first?.id == id, "failed save retains the searchable version")
        try reopened.delete(id)
        try reopened.delete(id)
        let deletedMatches = await reopened.search("替换后的答案标记")
        expect(deletedMatches.isEmpty, "delete invalidates searchable text")
        expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent(id.uuidString).path), "delete removes messages and image")
        expect(ChatSessionStore(root: root).summaries.count == ChatSessionStore.maximumSessions - 1, "deletion survives reopen")
        try reopened.deleteAll()
        let clearedMatches = await reopened.search("全文检索标记")
        expect(clearedMatches.isEmpty, "delete all invalidates searchable text")
        expect(!FileManager.default.fileExists(atPath: root.path), "delete all clears attachment directory")
        expect(!isSafeWebURL(URL(string: "file:///private/example")!), "non web source excluded")
        expect(!isSafeWebURL(URL(string: "https://user:password@example.com")!), "credential URL excluded")
        print("Chat history contracts passed: \(checks)")
    }

    private static func tryCount(_ store: ChatSessionStore, id: UUID) -> Int {
        (try? store.load(id).0.turns.count) ?? -1
    }
}
