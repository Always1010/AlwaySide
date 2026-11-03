import Combine
import Foundation

@MainActor
final class CompanionModel: ObservableObject {
    let capture = ScreenCaptureController()
    let credentials = CredentialStore()
    @Published private(set) var provider: ModelProvider
    @Published private(set) var keyAvailable = false
    @Published private(set) var snapshot: EvidenceSnapshot?
    @Published private(set) var history: [ConversationTurn] = []
    @Published private(set) var answer = ""
    @Published private(set) var isAsking = false
    @Published private(set) var notice: String?
    @Published var question = ""
    @Published var confirmed = false
    private var requestTask: Task<Void, Never>?
    private var requestID: UUID?
    private var captureObservation: AnyCancellable?
    private var returnFrame: CapturedFrame?

    init() {
        provider = ModelProvider(rawValue: UserDefaults.standard.string(forKey: "provider") ?? "") ?? .openAI
        refreshKeyStatus()
        captureObservation = capture.$state.dropFirst().sink { [weak self] state in
            guard let self else { return }
            if state == .stopped || state == .failed || state == .idle {
                self.returnFrame = nil
                if self.snapshot?.isImported == false { self.clearEvidence() }
            }
        }
    }

    var canAsk: Bool {
        keyAvailable && snapshot != nil && confirmed && !isAsking &&
        !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && question.count <= 2000
    }

    func selectProvider(_ value: ModelProvider) {
        guard provider != value else { return }
        cancelQuestion()
        provider = value
        UserDefaults.standard.set(value.rawValue, forKey: "provider")
        history = []
        answer = ""
        confirmed = false
        notice = "已切换供应商，追问记录已清空。请重新确认待发送的画面。"
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

    func freezeLatest() {
        guard capture.state == .capturing, let frame = returnFrame ?? capture.latestFrame else {
            notice = "尚未收到可用画面，请先共享内容。"
            return
        }
        // A return-to-App candidate is consumed once. Subsequent taps explicitly take a new frame.
        returnFrame = nil
        choose(EvidenceSnapshot(frame: frame, origin: "共享画面（时间为接收时间）", isImported: false))
    }

    func rememberReturnFrame() {
        // Best effort: samples arriving around the scene transition can still include this App.
        // A chosen snapshot is never replaced by this candidate or a new live frame.
        returnFrame = capture.freezeRecentFrame(before: ProcessInfo.processInfo.systemUptime)
    }

    func enteredBackground() {
        cancelQuestion()
        confirmed = false
    }

    func choose(_ value: EvidenceSnapshot) {
        cancelQuestion()
        snapshot = value
        confirmed = false
        history = []
        answer = ""
        notice = "已固定这一张画面。新画面开启新的上下文，请预览确认。"
    }

    func reportPhotoError() { notice = "图片导入失败或文件过大，请选择较小的截图。" }

    func ask() {
        guard canAsk, let evidence = snapshot else {
            notice = "请配置密钥、取帧并确认预览，再输入问题。"
            return
        }
        let prompt = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let key: String
        do {
            guard let stored = try credentials.read(provider), !stored.isEmpty else {
                throw ClientError.missingKey
            }
            key = stored
        } catch {
            notice = (error as? ClientError ?? .keychain).localizedDescription
            return
        }
        let id = UUID()
        let selectedProvider = provider
        let context = history
        requestID = id
        confirmed = false
        isAsking = true
        answer = ""
        notice = "正在请求 \(selectedProvider.name)，本次只发送已确认的这一张画面与最多 3 轮文字追问。"
        requestTask = Task { [weak self] in
            do {
                let result = try await VisionClient().answer(provider: selectedProvider, key: key,
                                                             question: prompt, snapshot: evidence, history: context)
                guard !Task.isCancelled, let self, self.requestID == id else { return }
                self.answer = result.text
                self.notice = result.complete ? "回答完成。追问仍基于这一张画面。" : "回答未完成，不加入追问上下文。请手动决定是否重试。"
                if result.complete {
                    self.history.append(ConversationTurn(question: prompt, answer: result.text))
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

    func cancelQuestion() {
        let wasAsking = isAsking
        requestID = nil
        requestTask?.cancel()
        requestTask = nil
        isAsking = false
        if wasAsking { notice = ClientError.cancelled.localizedDescription }
    }

    func clearEvidence() {
        cancelQuestion()
        snapshot = nil
        returnFrame = nil
        confirmed = false
        history = []
        answer = ""
        question = ""
    }

    private func finishRequest() {
        isAsking = false
        requestID = nil
        requestTask = nil
    }
}
