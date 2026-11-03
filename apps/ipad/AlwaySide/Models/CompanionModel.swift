import Combine
import Foundation
import UIKit

@MainActor
final class CompanionModel: ObservableObject {
    let capture = ScreenCaptureController()
    let credentials = CredentialStore()
    @Published private(set) var provider: ModelProvider
    @Published private(set) var keyAvailable = false
    @Published private(set) var snapshot: EvidenceSnapshot?
    @Published private(set) var history: [ConversationTurn] = []
    @Published private(set) var answer = ""
    @Published private(set) var answerProvider: ModelProvider?
    @Published private(set) var citations: [AnswerCitation] = []
    @Published private(set) var requestStatus: VisionRequestStatus?
    @Published private(set) var searchState: AnswerSearchState = .notUsed
    @Published private(set) var hasFinalAnswer = false
    @Published private(set) var isAsking = false
    @Published private(set) var notice: String?
    @Published private(set) var frameStatus = "共享后，在目标页面停留，再收起并唤起助手。"
    @Published var question = ""
    @Published var openAISettings: OpenAISettings {
        didSet {
            if let data = try? JSONEncoder().encode(openAISettings) {
                UserDefaults.standard.set(data, forKey: "openAISettings")
            }
        }
    }
    private var requestTask: Task<Void, Never>?
    private var requestID: UUID?
    private var captureObservation: AnyCancellable?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var interactionLocked = true
    private var activationFrame: CapturedFrame?
    private var activationBoundary: TimeInterval?
    private weak var interactionWindow: UIWindow?
    private var focusDeparture: TimeInterval?
    private var internalPresentation = false
    private var keyboardVisible = false

    init() {
        provider = ModelProvider(rawValue: UserDefaults.standard.string(forKey: "provider") ?? "") ?? .openAI
        if let data = UserDefaults.standard.data(forKey: "openAISettings"),
           let stored = try? JSONDecoder().decode(OpenAISettings.self, from: data) {
            openAISettings = stored
        } else { openAISettings = .defaults }
        refreshKeyStatus()
        captureObservation = capture.$state.dropFirst().sink { [weak self] state in
            guard let self, state != .capturing else { return }
            self.activationFrame = nil
            self.activationBoundary = nil
            self.clearEvidence()
            self.frameStatus = "共享后，在目标页面停留，再收起并唤起助手。"
        }
    }

    var activeModelName: String { provider == .openAI ? openAISettings.modelID : provider.model }
    var defaultQuestion: String { openAISettings.defaultQuestion }
    var canAsk: Bool {
        keyAvailable && snapshot != nil && !isAsking && question.count <= 2000 &&
        (provider != .openAI || openAISettings.validationMessage == nil)
    }

    func selectProvider(_ value: ModelProvider) {
        guard !isAsking, provider != value else { return }
        // A running request owns a configuration snapshot; changing settings affects the next request.
        provider = value
        UserDefaults.standard.set(value.rawValue, forKey: "provider")
        history = []
        answer = ""
        answerProvider = nil
        citations = []
        searchState = .notUsed
        hasFinalAnswer = false
        notice = "已切换供应商，下一次提问使用新配置。"
        refreshKeyStatus()
    }

    func refreshKeyStatus() {
        do { keyAvailable = try credentials.read(provider)?.isEmpty == false }
        catch { keyAvailable = false; notice = ClientError.keychain.localizedDescription }
    }

    func startSharing() {
        clearEvidence()
        notice = nil
        capture.presentPicker()
    }

    func stopSharing() async {
        clearEvidence()
        await capture.stopCapture()
        notice = "已停止共享并清空画面及追问。已发送的数据无法撤回。"
    }

    /// An actual background transition arms an early foreground boundary. Sheets and
    /// temporary inactive states do not; foreground key handoffs use their own bounds.
    func enteredBackground() {
        interactionLocked = false
        activationFrame = nil
        activationBoundary = nil
        focusDeparture = nil
        keyboardVisible = false
    }

    func bindInteractionWindow(_ window: UIWindow) { interactionWindow = window }

    /// Used only by an explicit edit action, never when the assistant appears.
    func prepareExplicitQuestionEditing() -> ObjectIdentifier? {
        guard let window = questionEditingWindow else { return nil }
        focusDeparture = nil
        window.endEditing(true)
        return ObjectIdentifier(window)
    }

    func activateExplicitQuestionEditing(in windowID: ObjectIdentifier) -> Bool {
        guard let window = questionEditingWindow, ObjectIdentifier(window) == windowID else { return false }
        // An intentional keyboard recovery is not a new external-app frame boundary.
        focusDeparture = nil
        if !window.isKeyWindow { window.makeKey() }
        return window.isKeyWindow
    }

    private var questionEditingWindow: UIWindow? {
        guard !isAsking, !internalPresentation, let window = interactionWindow,
              !window.isHidden, window.alpha > 0, window.windowLevel == .normal,
              window.windowScene?.activationState == .foregroundActive else { return nil }
        return window
    }

    func setInternalPresentation(_ presented: Bool) {
        internalPresentation = presented
        if presented { focusDeparture = nil }
    }

    func setKeyboardVisible(_ visible: Bool) {
        keyboardVisible = visible
        if visible { focusDeparture = nil }
    }

    func sceneWillDeactivate() { focusDeparture = nil }

    func windowResignedKey(_ window: UIWindow) {
        guard window === interactionWindow, interactionLocked, !internalPresentation, !keyboardVisible,
              window.windowScene?.activationState == .foregroundActive else {
            focusDeparture = nil
            return
        }
        focusDeparture = ProcessInfo.processInfo.systemUptime
    }

    func windowBecameKey(_ window: UIWindow) {
        guard window === interactionWindow else {
            // Another window belonging to this app is not an external-app handoff.
            focusDeparture = nil
            return
        }
        if !interactionLocked { lockFrameBeforeInteraction(); return }
        guard let departure = focusDeparture else { return }
        focusDeparture = nil
        let boundary = ProcessInfo.processInfo.systemUptime
        guard !internalPresentation, !keyboardVisible,
              window.windowScene?.activationState == .foregroundActive,
              boundary - departure >= 2.5 else { return }
        // Scene-level key changes are only a heuristic. The app can still be visible
        // inside a full-display capture; never claim this authenticates the target App.
        preserveActivationFrame(capture.frameBeforeInteraction(at: boundary, after: departure + 1), at: boundary)
    }

    /// Called at willEnterForeground, then (as fallbacks) window-key/scene/focus events.
    /// The first event wins. No API request is made, and an existing conversation is retained.
    func lockFrameBeforeInteraction() {
        guard !interactionLocked else { return }
        interactionLocked = true
        let boundary = ProcessInfo.processInfo.systemUptime
        preserveActivationFrame(capture.frameBeforeInteraction(at: boundary), at: boundary)
    }

    private func preserveActivationFrame(_ frame: CapturedFrame?, at boundary: TimeInterval) {
        activationBoundary = boundary
        activationFrame = frame
        guard let frame = activationFrame else {
            frameStatus = "未取得可靠的唤起前画面。请回到目标页面停留片刻，收起后再打开。"
            return
        }
        frameStatus = "已保留唤起前画面，请展开核对；接收时间不代表来源。"
        if snapshot == nil {
            choose(EvidenceSnapshot(frame: frame, origin: "唤起前共享画面（待核对）", isImported: false))
        }
    }

    func updateCurrentFrame() {
        guard let frame = activationFrame, let boundary = activationBoundary,
              ProcessInfo.processInfo.systemUptime - boundary <= 60,
              snapshot?.id != frame.id else {
            notice = "没有新的唤起前画面。请收起助手，在目标页面停留后再打开；原图追问保持不变。"
            return
        }
        choose(EvidenceSnapshot(frame: frame, origin: "唤起前共享画面（待核对）", isImported: false))
    }

    func choose(_ value: EvidenceSnapshot) {
        cancelQuestion()
        snapshot = value
        history = []
        answer = ""
        answerProvider = nil
        citations = []
        searchState = .notUsed
        hasFinalAnswer = false
        if value.isImported { frameStatus = "已固定导入截图。后续追问沿用这张图片。" }
        notice = "已固定画面；发送即可提问，空问题使用默认提问。更新画面会另起上下文。"
    }

    func reportPhotoError() { notice = "图片导入失败或文件过大，请选择较小的截图。" }

    func ask() {
        guard canAsk, let evidence = snapshot else {
            notice = "请先保存密钥并取得画面；OpenAI 配置需兼容当前型号。"
            return
        }
        let typedQuestion = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let prompt = typedQuestion.isEmpty ? defaultQuestion : typedQuestion
        guard !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, prompt.count <= 2000 else {
            notice = "请在设置中填写不超过 2000 字的默认问题。"; return
        }
        let key: String
        do {
            guard let stored = try credentials.read(provider), !stored.isEmpty else { throw ClientError.missingKey }
            key = stored
        } catch {
            notice = (error as? ClientError ?? .keychain).localizedDescription
            return
        }
        let id = UUID()
        let selectedProvider = provider
        let settings = openAISettings
        let context = history
        requestID = id
        isAsking = true
        answer = ""
        answerProvider = selectedProvider
        citations = []
        searchState = .notUsed
        hasFinalAnswer = false
        requestStatus = .connecting
        notice = "正在发送固定画面与问题至 \(selectedProvider.name)；追问沿用此图。"
        // Request the finite assertion before starting work, rather than waiting for suspension.
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "AlwaySide answer") { [weak self] in
            guard let self, self.requestID == id else { return }
            self.cancelQuestion()
            self.notice = "系统后台时间已结束，已停止等待。返回后可手动重试；已发送数据无法撤回。"
        }
        requestTask = Task { [weak self] in
            do {
                let result = try await VisionClient().answer(provider: selectedProvider, key: key,
                    question: prompt, snapshot: evidence, history: context, openAISettings: settings,
                    onEvent: { [weak self] event in
                        await self?.receive(event, requestID: id)
                    })
                guard !Task.isCancelled, let self, self.requestID == id else { return }
                self.answer = result.text
                self.citations = result.citations
                self.searchState = result.searchState
                self.hasFinalAnswer = true
                self.notice = result.truncated ? "回答超过本地 12,000 字显示上限，已截断；未加入追问。" :
                    (result.complete ? "回答完成。追问仍基于这一张画面。" : "回答未完成，未加入追问上下文；可手动重试。")
                if result.complete, self.provider == selectedProvider {
                    self.history.append(ConversationTurn(question: prompt, answer: result.text,
                        citations: result.citations, searchState: result.searchState))
                    self.history = Array(self.history.suffix(3))
                    self.question = ""
                }
                self.finishRequest()
            } catch {
                guard let self, self.requestID == id else { return }
                self.notice = (error as? ClientError ?? .invalidResponse).localizedDescription
                self.finishRequest()
            }
        }
    }

    private func receive(_ event: VisionStreamEvent, requestID id: UUID) {
        guard requestID == id, isAsking else { return }
        switch event {
        case .text(let text): answer = text
        case .status(let status): requestStatus = status
        }
    }

    func cancelQuestion() {
        let wasAsking = isAsking
        requestID = nil
        requestTask?.cancel()
        finishRequest()
        if wasAsking { notice = ClientError.cancelled.localizedDescription }
    }

    func clearEvidence() {
        cancelQuestion()
        snapshot = nil
        activationFrame = nil
        activationBoundary = nil
        focusDeparture = nil
        history = []
        answer = ""
        answerProvider = nil
        citations = []
        searchState = .notUsed
        hasFinalAnswer = false
        question = ""
    }

    func discardEvidence() {
        clearEvidence()
        capture.discardRecentFrames()
        frameStatus = "画面已清空，请回目标页面停留后重新唤起。"
        notice = capture.state == .capturing ? "已清空画面和追问；共享继续在本地接收。" : "已清空画面和追问。"
    }

    private func finishRequest() {
        isAsking = false
        requestID = nil
        requestTask = nil
        requestStatus = nil
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }
}
