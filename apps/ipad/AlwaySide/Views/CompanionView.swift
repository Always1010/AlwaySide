import PhotosUI
import SwiftUI
import UIKit

struct CompanionView: View {
    @ObservedObject var model: CompanionModel
    @ObservedObject var capture: ScreenCaptureController
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingSettings = false
    @State private var photoSelection: PhotosPickerItem?
    @State private var photoTask: Task<Void, Never>?
    @State private var photoRequestID: UUID?
    @State private var importing = false

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        introduction
                        if geometry.size.width >= 850 {
                            HStack(alignment: .top, spacing: 24) {
                                evidencePanel.frame(maxWidth: .infinity)
                                conversationPanel.frame(maxWidth: .infinity)
                            }
                        } else {
                            evidencePanel
                            conversationPanel
                        }
                    }
                    .padding(24)
                    .frame(maxWidth: 1400)
                    .frame(maxWidth: .infinity)
                }
                .background(Color(.systemGroupedBackground))
            }
            .navigationTitle("AlwaySide")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingSettings = true } label: {
                        Image(systemName: "key.horizontal")
                    }
                    .help("配置本机 API Key")
                    .accessibilityLabel("配置本机 API Key")
                    .disabled(model.isAsking)
                }
            }
            .sheet(isPresented: $showingSettings) { ProviderSettingsView(model: model) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { model.rememberReturnFrame() }
                if phase == .background { model.enteredBackground() }
            }
            .onChange(of: photoSelection) { _, selection in
                photoTask?.cancel()
                guard let selection else { return }
                let requestID = UUID()
                photoRequestID = requestID
                importing = true
                photoTask = Task { @MainActor in
                    defer {
                        if photoRequestID == requestID {
                            importing = false
                            photoTask = nil
                            photoRequestID = nil
                        }
                    }
                    do {
                        guard let data = try await selection.loadTransferable(type: Data.self) else {
                            throw ClientError.invalidImage
                        }
                        try Task.checkCancellation()
                        let snapshot = try PhotoImport.snapshot(from: data)
                        guard !Task.isCancelled, photoRequestID == requestID else { return }
                        model.choose(snapshot)
                    } catch {
                        if !Task.isCancelled, photoRequestID == requestID { model.reportPhotoError() }
                    }
                }
            }
            .onDisappear { cancelPhotoImport() }
        }
    }

    private var introduction: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("一起看，随时问。")
                .font(.largeTitle.bold())
            Text("共享内容或导入截图，固定一张画面并确认，再向所选模型提问。")
                .font(.title3).foregroundStyle(.secondary)
            Text("预览和配置不调用 API。点击提问才发送图片，使用你自己账户的 API 用量。首版提供画面与文字问答。")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var evidencePanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Label("画面", systemImage: "rectangle.on.rectangle")
                    .font(.title2.bold())
                Spacer()
                Text(captureStatus).font(.caption).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button { model.startSharing() } label: {
                    Label("开始共享", systemImage: "rectangle.inset.filled.and.person.filled")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isAsking || importing || capture.state == .requesting ||
                          capture.state == .starting || capture.state == .capturing)
                Button {
                    cancelPhotoImport()
                    Task { await model.stopSharing() }
                } label: { Image(systemName: "stop.fill") }
                .buttonStyle(.bordered)
                .help("停止共享，取消请求并清空画面与追问")
                .accessibilityLabel("停止共享并清空画面与追问")
                Spacer(minLength: 0)
                PhotosPicker(selection: $photoSelection, matching: .images, photoLibrary: .shared()) {
                    Image(systemName: "photo.badge.plus")
                }
                .buttonStyle(.bordered)
                .help("导入一张截图作为备选输入")
                .accessibilityLabel("导入一张截图作为备选输入")
                .disabled(model.isAsking || importing)
            }
            if importing { ProgressView("正在本机读取和缩小图片…") }
            preview
            if let snapshot = model.snapshot {
                Text("已固定 · \(snapshot.origin)\n\(snapshot.frame.timestamp.formatted(date: .abbreviated, time: .standard)) · \(snapshot.frame.width) × \(snapshot.frame.height)")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Text("回答和追问都基于这一张画面；新取帧会清空旧的追问上下文。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Button { model.freezeLatest() } label: {
                Label("取帧并固定预览", systemImage: "camera.viewfinder")
            }
            .buttonStyle(.bordered)
            .disabled(capture.state != .capturing || capture.latestFrame == nil || model.isAsking || importing)
            Text("返回助手后的第一次取帧优先使用切换前的画面，再次取帧会更新为最新画面。共享范围由系统选择；请核对预览和时间，避免把助手自身或无关内容发送出去。")
                .font(.footnote).foregroundStyle(.secondary)
            if let error = capture.lastError {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.footnote).foregroundStyle(.orange)
            }
        }
        .padding(20)
        .background(.background, in: RoundedRectangle(cornerRadius: 20))
    }

    @ViewBuilder private var preview: some View {
        let data = model.snapshot?.frame.jpegData ?? capture.latestFrame?.jpegData
        VStack(spacing: 8) {
            if let data, let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxHeight: 420)
                    .accessibilityLabel(model.snapshot == nil ? "实时预览，尚未固定" : "本次问题的固定画面")
            } else {
                ContentUnavailableView("还没有画面", systemImage: "rectangle.dashed",
                                       description: Text("开始系统共享，或用右上方照片按钮选一张截图。"))
                    .frame(minHeight: 230)
            }
            if model.snapshot == nil, let frame = capture.latestFrame {
                Text("实时预览 · 接收于 \(frame.timestamp.formatted(date: .omitted, time: .standard)) · 尚未固定")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var conversationPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("问问 AlwaySide", systemImage: "bubble.left.and.bubble.right")
                .font(.title2.bold())
            Picker("模型供应商", selection: Binding(get: { model.provider }, set: { model.selectProvider($0) })) {
                ForEach(ModelProvider.allCases) { provider in Text(provider.name).tag(provider) }
            }
            .pickerStyle(.segmented).disabled(model.isAsking)
            HStack {
                Text(model.provider.model).font(.caption.monospaced()).foregroundStyle(.secondary)
                Spacer()
                Text(model.keyAvailable ? "密钥已存本机" : "请先配置密钥")
                    .font(.caption).foregroundStyle(model.keyAvailable ? Color.secondary : Color.orange)
            }
            TextField("这段画面里，你想知道什么？", text: $model.question, axis: .vertical)
                .lineLimit(3...7).textFieldStyle(.roundedBorder)
                .disabled(model.isAsking)
                .onChange(of: model.question) { _, value in
                    if value.count > 2000 { model.question = String(value.prefix(2000)) }
                }
            Toggle("我已核对预览，同意将这张图片和问题发给 \(model.provider.name)", isOn: $model.confirmed)
                .font(.callout).disabled(model.snapshot == nil || model.isAsking)
            HStack {
                Button { model.ask() } label: { Label("提问", systemImage: "arrow.up.circle.fill") }
                    .buttonStyle(.borderedProminent).disabled(!model.canAsk || importing)
                if model.isAsking {
                    ProgressView().controlSize(.small)
                    Text("正在处理…").font(.callout).foregroundStyle(.secondary)
                    Spacer()
                    Button { model.cancelQuestion() } label: { Image(systemName: "xmark.circle") }
                        .help("取消此问题")
                        .accessibilityLabel("取消此问题")
                }
            }
            if let notice = model.notice {
                Text(notice).font(.footnote).foregroundStyle(.secondary)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            if !model.answer.isEmpty {
                Divider()
                Text("回答").font(.headline)
                Text(model.answer).textSelection(.enabled)
            }
            if !model.history.isEmpty {
                DisclosureGroup("同一画面的最近 \(model.history.count) 轮追问") {
                    ForEach(model.history) { turn in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(turn.question).font(.callout.bold())
                            Text(turn.answer).font(.callout).textSelection(.enabled)
                        }.padding(.vertical, 8)
                    }
                }
            }
            Text("图片仅保存在当前内存中，最多携带 3 轮文字追问。停止共享会取消请求并清空画面和上下文；已发送至供应商的数据无法撤回，取消不保证免计用量。")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .padding(20)
        .background(.background, in: RoundedRectangle(cornerRadius: 20))
    }

    private var captureStatus: String {
        switch capture.state {
        case .idle: return "未共享"
        case .requesting: return "等待系统选择"
        case .starting: return "正在开始"
        case .capturing: return "共享中 · 仅画面"
        case .stopped: return "已停止"
        case .failed: return "共享失败"
        }
    }

    private func cancelPhotoImport() {
        photoRequestID = nil
        photoTask?.cancel()
        photoTask = nil
        importing = false
        photoSelection = nil
    }
}
