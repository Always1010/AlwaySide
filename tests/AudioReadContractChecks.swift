import Foundation

/// Runs the production coordinator with fake audio/model operations; no API or device access.
private actor AudioReadProbe {
    private(set) var transcriptions = 0
    private(set) var explanations = 0
    private(set) var cancellations = 0
    private(set) var prompt = ""
    private var transcriptionWaiters: [CheckedContinuation<Void, Never>] = []
    private var explanationWaiters: [CheckedContinuation<Void, Never>] = []

    func waitForTranscription() async {
        if transcriptions > 0 { return }
        await withCheckedContinuation { transcriptionWaiters.append($0) }
    }

    func waitForExplanation() async {
        if explanations > 0 { return }
        await withCheckedContinuation { explanationWaiters.append($0) }
    }

    func transcribe(suspend: Bool = false, fail: Bool = false) async throws -> String {
        transcriptions += 1
        transcriptionWaiters.forEach { $0.resume() }; transcriptionWaiters.removeAll()
        if fail { throw AudioError.empty }
        if suspend {
            // Deliberately return late after cancellation to exercise generation isolation.
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch { cancellations += 1 }
        }
        return "  That’s a stretch.\n"
    }

    func explain(_ text: String, onEvent: @Sendable (VisionStreamEvent) async -> Void,
                 failOnce: Bool = false, suspend: Bool = false) async throws -> ModelAnswer {
        explanations += 1
        prompt = text
        await onEvent(.text("翻译中的片段"))
        explanationWaiters.forEach { $0.resume() }; explanationWaiters.removeAll()
        if failOnce && explanations == 1 { throw ClientError.network }
        if suspend {
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch { cancellations += 1 }
            await onEvent(.text("旧请求的迟到片段"))
        }
        return ModelAnswer(text: "翻译：这有点牵强。\n解释：推论超出了证据。", complete: true)
    }
}

@MainActor
@main
private struct AudioReadContractChecks {
    private static var checks = 0
    private static func expect(_ value: @autoclosure () -> Bool, _ label: String) {
        guard value() else { fatalError("FAIL: \(label)") }
        checks += 1
    }

    static func main() async {
        let store = AudioReadStore()
        let probe = AudioReadProbe()
        let originalID = store.id
        await store.process(transcribe: { try await probe.transcribe() }, explain: { text, event in
            try await probe.explain(text, onEvent: event, failOnce: true)
        })
        expect(store.transcript == "That’s a stretch.", "original transcript remains available when explanation fails")
        expect(store.answer == "翻译中的片段", "partial explanation remains readable")
        expect(store.result?.answer.complete == false && store.failure != nil, "partial output is not treated as complete")
        let firstPrompt = await probe.prompt
        expect(firstPrompt.contains("<音频转写>\nThat’s a stretch.\n</音频转写>"), "explanation uses the full normalized transcript")
        await store.process(transcribe: { try await probe.transcribe() }, explain: { text, event in
            try await probe.explain(text, onEvent: event, failOnce: true)
        })
        let transcriptions = await probe.transcriptions
        let explanations = await probe.explanations
        expect(transcriptions == 1 && explanations == 2, "retry after explanation failure never uploads audio twice")
        expect(store.result?.id == originalID && store.result?.answer.complete == true, "retry replaces the same clip without duplicate history")
        expect(store.failure == nil && store.phase == .idle, "successful retry clears failure and busy state")
        await store.process(transcribe: { try await probe.transcribe() }, explain: { text, event in
            try await probe.explain(text, onEvent: event)
        })
        let callsAfterCompletion = await probe.explanations
        expect(callsAfterCompletion == 2, "reading a completed result never starts another request")

        store.reset()
        let failureProbe = AudioReadProbe()
        await store.process(transcribe: { try await failureProbe.transcribe(fail: true) }, explain: { text, event in
            try await failureProbe.explain(text, onEvent: event)
        })
        let failedExplanations = await failureProbe.explanations
        expect(failedExplanations == 0 && store.transcript.isEmpty, "failed transcription never explains an old clip")
        expect(store.result == nil && store.failure != nil, "failed transcription is visible without stale results")

        let lateProbe = AudioReadProbe()
        let lateRequest = Task {
            await store.process(transcribe: { try await lateProbe.transcribe(suspend: true) }, explain: { text, event in
                try await lateProbe.explain(text, onEvent: event)
            })
        }
        await lateProbe.waitForTranscription()
        await store.process(transcribe: { "duplicate" }, explain: { _, _ in
            fatalError("duplicate submission must be rejected")
        })
        store.reset()
        let replacementID = store.id
        await store.process(transcribe: { "New clip" }, explain: { _, event in
            await event(.text("新片段解释"))
            return ModelAnswer(text: "新片段解释", complete: true)
        })
        await lateRequest.value
        let lateExplanations = await lateProbe.explanations
        let lateCancellations = await lateProbe.cancellations
        expect(lateExplanations == 0 && lateCancellations == 1, "replacing a clip cancels transcription before explanation")
        expect(store.result?.id == replacementID && store.transcript == "New clip", "late transcript cannot overwrite replacement")

        store.reset()
        let cancelledProbe = AudioReadProbe()
        let cancelledRequest = Task {
            await store.process(transcribe: { try await cancelledProbe.transcribe() }, explain: { text, event in
                try await cancelledProbe.explain(text, onEvent: event, suspend: true)
            })
        }
        await cancelledProbe.waitForExplanation()
        store.cancel()
        await cancelledRequest.value
        let explanationCancellations = await cancelledProbe.cancellations
        expect(explanationCancellations == 1, "stop propagates cancellation to the explanation operation")
        expect(store.answer == "翻译中的片段" && store.result?.answer.complete == false,
               "late stream events and success cannot replace cancelled partial output")
        expect(!store.isRunning && !store.transcript.isEmpty, "stop preserves the original for an explicit retry")

        store.reset()
        let parentProbe = AudioReadProbe()
        let parent = Task {
            await store.process(transcribe: { try await parentProbe.transcribe(suspend: true) }, explain: { _, _ in
                fatalError("cancelled parent must never explain")
            })
        }
        await parentProbe.waitForTranscription()
        parent.cancel()
        await parent.value
        let parentCancellations = await parentProbe.cancellations
        expect(parentCancellations == 1 && !store.isRunning && store.result == nil,
               "caller cancellation ends the chain and clears processing state")
        print("Audio reading contracts passed: \(checks)")
    }
}
