import Foundation

/// Production coordinator with fake operations: no API, credentials, UI, or real screenshots.
private actor QuickReadProbe {
    private(set) var calls = 0
    private(set) var cancellations = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func waitUntilStarted() async {
        if calls > 0 { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func answer(_ value: ModelAnswer, suspended: Bool = false,
                ignoresCancellation: Bool = false) async throws -> ModelAnswer {
        calls += 1
        waiters.forEach { $0.resume() }
        waiters.removeAll()
        if suspended {
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch {
                cancellations += 1
                if !ignoresCancellation { throw error }
            }
        }
        return value
    }
}

@MainActor
@main
private struct QuickReadContractChecks {
    private static var checks = 0
    private static func expect(_ value: @autoclosure () -> Bool, _ label: String) {
        guard value() else { fatalError("FAIL: \(label)") }
        checks += 1
    }

    static func main() async throws {
        let frame = CapturedFrame(id: UUID(), timestamp: Date(), monotonicSeconds: 0,
            jpegData: Data([1, 2, 3]), width: 2, height: 3)
        let snapshot = EvidenceSnapshot(frame: frame, origin: "fixture", isImported: true)
        let citation = AnswerCitation(url: URL(string: "https://example.com/source")!, title: "来源")
        let answer = ModelAnswer(text: "阅读答案", complete: true,
                                 citations: [citation], searchState: .completed)
        let store = QuickReadStore()
        let first = store.begin()
        let probe = QuickReadProbe()
        try await store.run(id: first, snapshot: snapshot, provider: .openAI) {
            try await probe.answer(answer)
        }
        for _ in 0..<5 {
            expect(store.result(for: first)?.answer.text == answer.text, "repeat rendering reads the same answer")
        }
        let calls = await probe.calls
        expect(calls == 1, "rendering never repeats the model request")
        expect(store.takeHandoff() == nil, "a ready card does not open the app")
        try store.prepareHandoff(first)
        let handoff = store.takeHandoff()
        expect(handoff?.snapshot.frame.jpegData == frame.jpegData, "handoff retains the exact screenshot")
        expect(handoff?.snapshot.frame.timestamp == frame.timestamp, "handoff preserves receipt time")
        expect(handoff?.answer.citations == [citation], "handoff preserves actual sources")
        expect(handoff?.answer.complete == true, "handoff preserves completion state")
        expect(store.takeHandoff() == nil, "foreground notifications consume handoff once")
        expect(store.result(for: first, now: Date().addingTimeInterval(601)) == nil, "expired result cannot be rendered")
        expect(store.result(for: UUID()) == nil, "an unrelated id cannot recover the latest screenshot")

        let second = store.begin()
        expect(store.result(for: first) == nil, "new input invalidates an older answer before import")
        store.fail(second, message: "无效截图")
        expect(store.result(for: second) == nil, "invalid input cannot reuse a previous screenshot")
        expect(store.failure(for: second) == "无效截图", "input failure remains visible")
        do { try store.prepareHandoff(second); fatalError("failed input must not open old content") }
        catch is ScreenshotHandoffError { checks += 1 }
        store.fail(first, message: "旧请求的迟到错误")
        expect(store.failure(for: second) == "无效截图", "late failures cannot overwrite new state")

        let old = store.begin()
        let oldProbe = QuickReadProbe()
        let oldRequest = Task {
            try await store.run(id: old, snapshot: snapshot, provider: .openAI) {
                try await oldProbe.answer(answer, suspended: true, ignoresCancellation: true)
            }
        }
        await oldProbe.waitUntilStarted()
        let newest = store.begin()
        try await store.run(id: newest, snapshot: snapshot, provider: .openAI) {
            ModelAnswer(text: "新页面答案", complete: true)
        }
        do { try await oldRequest.value; fatalError("superseded operation must cancel") }
        catch is CancellationError { checks += 1 }
        let oldCancellations = await oldProbe.cancellations
        expect(oldCancellations == 1, "new screenshot cancels underlying work")
        expect(store.result(for: old) == nil, "late response from cancelled work is inaccessible")
        expect(store.result(for: newest)?.answer.text == "新页面答案", "late success cannot overwrite the new answer")

        let cancelID = store.begin()
        let cancelProbe = QuickReadProbe()
        let cancelledRequest = Task {
            try await store.run(id: cancelID, snapshot: snapshot, provider: .openAI) {
                try await cancelProbe.answer(answer, suspended: true)
            }
        }
        await cancelProbe.waitUntilStarted()
        cancelledRequest.cancel()
        do { try await cancelledRequest.value; fatalError("caller cancellation must propagate") }
        catch is CancellationError { checks += 1 }
        let cancellations = await cancelProbe.cancellations
        expect(cancellations == 1 && store.result(for: cancelID) == nil, "system cancellation stops work and discards result")

        let timeoutID = store.begin()
        let timeoutProbe = QuickReadProbe()
        do {
            try await store.run(id: timeoutID, snapshot: snapshot, provider: .openAI,
                                timeoutNanoseconds: 5_000_000) {
                try await timeoutProbe.answer(answer, suspended: true)
            }
            fatalError("deadline must fail")
        } catch ClientError.timeout { checks += 1 }
        let timeouts = await timeoutProbe.cancellations
        let timeoutCalls = await timeoutProbe.calls
        expect(timeouts == 1 && timeoutCalls == 1, "deadline cancels without retry")
        expect(store.result(for: timeoutID) == nil, "timeout never becomes a completed answer")

        let retryID = store.begin()
        try await store.run(id: retryID, snapshot: snapshot, provider: .deepSeek) {
            ModelAnswer(text: "未完成片段", complete: false)
        }
        expect(store.result(for: retryID)?.answer.complete == false, "incomplete answer stays incomplete")
        expect(store.result(for: retryID)?.provider == .deepSeek, "provider identity survives handoff")
        store.clear(ifMatching: UUID())
        expect(store.result(for: retryID) != nil, "deleting unrelated history preserves the card")
        try store.prepareHandoff(retryID)
        store.clear(ifMatching: retryID)
        expect(store.result(for: retryID) == nil && store.takeHandoff() == nil,
               "deleting related history prevents resurrection from a card")
        let clearedID = store.begin()
        store.clear()
        expect(!store.isCurrent(clearedID), "clearing all history invalidates pending cards")
        print("QuickRead contracts passed: \(checks)")
    }
}
