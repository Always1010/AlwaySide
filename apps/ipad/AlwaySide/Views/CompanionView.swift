import ImageIO
import PhotosUI
import SwiftUI
import UIKit

struct CompanionView: View {
    @ObservedObject var model: CompanionModel
    @ObservedObject var capture: ScreenCaptureController
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var inputFocused: Bool
    @State private var showingSettings = false
    @State private var showingPhotoPicker = false
    @State private var previewExpanded = false
    @State private var photoSelection: PhotosPickerItem?
    @State private var photoTask: Task<Void, Never>?
    @State private var photoRequestID: UUID?
    @State private var importing = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                evidenceSummary
                Divider()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if let notice = model.notice {
                            Text(notice).font(.footnote).foregroundStyle(.secondary)
                                .accessibilityAddTraits(.updatesFrequently)
                        }
                        if model.answer.isEmpty {
                            ContentUnavailableView("一起看，随时问", systemImage: "bubble.left.and.bubble.right",
                                description: Text(model.snapshot == nil ? "共享画面后回到目标页面，再唤起助手；也可导入截图。" : "输入问题，或直接发送默认提问。唤起与预览不调用 API。"))
                        } else {
                            Text(model.answer).textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if let provider = model.answerProvider {
                                Text("回答来自 \(provider.name)").font(.caption).foregroundStyle(.secondary)
                            }
                            if model.answerProvider == .openAI && !model.isAsking {
                                Label(model.hasFinalAnswer ? model.searchState.label : "回答已中断，检索与来源状态未确认", systemImage: "globe")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            ForEach(model.citations) { citation in
                                Link(destination: citation.url) {
                                    Label(citation.title.isEmpty ? citation.url.host ?? "查看来源" : citation.title,
                                          systemImage: "link")
                                        .font(.callout).lineLimit(2)
                                }
                            }
                        }
                        if !model.history.isEmpty {
                            DisclosureGroup("同图最近 \(model.history.count) 轮") {
                                ForEach(model.history) { turn in
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(turn.question).font(.callout.bold())
                                        Text(turn.answer).font(.callout).textSelection(.enabled)
                                        if model.provider == .openAI {
                                            Text(turn.searchState.label).font(.caption).foregroundStyle(.secondary)
                                        }
                                        ForEach(turn.citations) { citation in
                                            Link(destination: citation.url) {
                                                Label(citation.title.isEmpty ? citation.url.host ?? "查看来源" : citation.title,
                                                      systemImage: "link")
                                                    .font(.caption).lineLimit(2)
                                            }
                                        }
                                    }.padding(.vertical, 8)
                                }
                            }.font(.footnote)
                        }
                    }.padding(16)
                }
                composer
            }
            .background(Color(.systemGroupedBackground))
            .background(InteractionWindowReader { model.bindInteractionWindow($0) }.frame(width: 0, height: 0))
            .navigationTitle("AlwaySide")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("供应商", selection: Binding(get: { model.provider }, set: { model.selectProvider($0) })) {
                            ForEach(ModelProvider.allCases) { Text($0.name).tag($0) }
                        }
                        .disabled(model.isAsking)
                        Button { cancelPhotoImport(); model.startSharing() } label: { Label("开始系统共享", systemImage: "rectangle.inset.filled.and.person.filled") }
                            .disabled(capture.state == .capturing || capture.state == .starting || capture.state == .requesting)
                        Button(role: .destructive) {
                            cancelPhotoImport()
                            Task { await model.stopSharing() }
                        } label: { Label("停止共享并清空", systemImage: "stop.fill") }
                        Button(role: .destructive) {
                            cancelPhotoImport()
                            model.discardEvidence()
                        } label: { Label("清空画面与追问", systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .help("供应商、共享与清空")
                    .accessibilityLabel("供应商、共享与清空")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { openSettings() } label: { Image(systemName: "slider.horizontal.3") }
                        .help("模型、指令与本机密钥")
                        .accessibilityLabel("模型、指令与本机密钥")
                }
            }
            .sheet(isPresented: $showingSettings) { ProviderSettingsView(model: model) }
            .photosPicker(isPresented: $showingPhotoPicker, selection: $photoSelection, matching: .images)
            .onChange(of: showingSettings) { _, _ in updatePresentationState() }
            .onChange(of: showingPhotoPicker) { _, _ in updatePresentationState() }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                model.lockFrameBeforeInteraction()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIWindow.didBecomeKeyNotification)) { notification in
                guard let window = notification.object as? UIWindow else { return }
                model.windowBecameKey(window)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIWindow.didResignKeyNotification)) { notification in
                guard let window = notification.object as? UIWindow else { return }
                model.windowResignedKey(window)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScene.willDeactivateNotification)) { _ in
                model.sceneWillDeactivate()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                model.setKeyboardVisible(true)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                model.setKeyboardVisible(false)
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background { model.enteredBackground() }
                if phase == .active { model.lockFrameBeforeInteraction() }
            }
            .onChange(of: inputFocused) { _, focused in
                if focused { previewExpanded = false; model.lockFrameBeforeInteraction() }
            }
            .onChange(of: photoSelection) { _, selection in importPhoto(selection) }
            .onDisappear { cancelPhotoImport() }
        }
    }

    private var evidenceSummary: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Text(model.activeModelName).font(.caption.monospaced()).lineLimit(1)
                Spacer(minLength: 4)
                Text(capture.state.title).font(.caption).foregroundStyle(.secondary)
                Button { model.updateCurrentFrame() } label: { Image(systemName: "camera.viewfinder") }
                    .help("用本次唤起前画面开启新上下文")
                    .accessibilityLabel("更新画面并开启新上下文")
                    .disabled(capture.state != .capturing || model.isAsking || importing)
                Button { model.setInternalPresentation(true); showingPhotoPicker = true } label: { Image(systemName: "photo.badge.plus") }
                .help("导入截图作为备选")
                .accessibilityLabel("导入截图作为备选")
                .disabled(model.isAsking || importing)
            }
            if importing { ProgressView("正在本机导入…").font(.caption) }
            if !model.keyAvailable {
                Button { openSettings() } label: { Label("先保存 API Key，其余设置已有默认值", systemImage: "key.horizontal") }
                    .font(.footnote)
            }
            if model.provider == .openAI, let validation = model.openAISettings.validationMessage {
                Label(validation, systemImage: "exclamationmark.circle")
                    .font(.caption).foregroundStyle(.orange)
            }
            DisclosureGroup(isExpanded: $previewExpanded) {
                if previewExpanded, let snapshot = model.snapshot {
                    EvidencePreview(frame: snapshot.frame)
                    Text("\(snapshot.origin) · 接收于 \(snapshot.frame.timestamp.formatted(date: .omitted, time: .standard))")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                Text(model.frameStatus).font(.caption).foregroundStyle(.secondary)
                Text("画面可能包含系统过渡或窗口遮挡。画面不符时，请收起后重新唤起，或导入截图。")
                    .font(.caption).foregroundStyle(.secondary)
            } label: {
                HStack(spacing: 8) {
                    if let snapshot = model.snapshot {
                        EvidencePreview(frame: snapshot.frame, maximumHeight: 40).frame(width: 54, height: 40)
                    }
                    Label(model.snapshot == nil ? "尚未固定画面" : "固定画面 · 展开核对", systemImage: "rectangle.on.rectangle")
                        .font(.footnote)
                }
            }
            if let error = capture.lastError { Text(error).font(.caption).foregroundStyle(.orange) }
            if capture.state != .capturing && capture.state != .starting && capture.state != .requesting {
                Button { cancelPhotoImport(); model.startSharing() } label: { Label("开始共享", systemImage: "rectangle.inset.filled.and.person.filled") }
                    .buttonStyle(.bordered).font(.footnote)
            }
        }.padding(.horizontal, 14).padding(.vertical, 10)
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let status = model.requestStatus {
                HStack { ProgressView().controlSize(.mini); Text(status.label).font(.caption) }
            }
            HStack(alignment: .bottom, spacing: 10) {
                TextField("输入问题；留空使用默认提问", text: $model.question, axis: .vertical)
                    .lineLimit(1...4).textFieldStyle(.roundedBorder)
                    .focused($inputFocused)
                    .disabled(model.isAsking || importing)
                    .onChange(of: model.question) { _, text in
                        if text.count > 2000 { model.question = String(text.prefix(2000)) }
                    }
                if model.isAsking {
                    Button { model.cancelQuestion() } label: { Image(systemName: "stop.circle.fill").font(.title) }
                        .help("停止本次回答")
                        .accessibilityLabel("停止本次回答")
                } else {
                    Button { inputFocused = false; model.ask() } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }
                        .help(model.question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "发送默认问题：\(model.defaultQuestion)" : "发送固定画面与问题")
                        .accessibilityLabel("发送固定画面与问题，留空使用默认问题")
                        .disabled(!model.canAsk || importing)
                }
            }
            Text("发送才上传固定图片与最多 3 轮文字；无音轨。收起后仅在系统允许时间内继续。")
                .font(.caption2).foregroundStyle(.secondary)
        }.padding(12).background(.background)
    }

    private func importPhoto(_ selection: PhotosPickerItem?) {
        photoTask?.cancel()
        guard let selection else { return }
        let id = UUID()
        photoRequestID = id
        importing = true
        photoTask = Task { @MainActor in
            defer {
                if photoRequestID == id { importing = false; photoTask = nil; photoRequestID = nil }
            }
            do {
                guard let data = try await selection.loadTransferable(type: Data.self) else { throw ClientError.invalidImage }
                try Task.checkCancellation()
                let snapshot = try PhotoImport.snapshot(from: data)
                guard !Task.isCancelled, photoRequestID == id else { return }
                model.choose(snapshot)
            } catch {
                if !Task.isCancelled, photoRequestID == id { model.reportPhotoError() }
            }
        }
    }

    private func updatePresentationState() {
        model.setInternalPresentation(showingSettings || showingPhotoPicker)
    }

    private func openSettings() {
        model.setInternalPresentation(true)
        showingSettings = true
    }

    private func cancelPhotoImport() {
        photoRequestID = nil
        photoTask?.cancel()
        photoTask = nil
        importing = false
        photoSelection = nil
    }
}

private struct EvidencePreview: View {
    let frame: CapturedFrame
    var maximumHeight: CGFloat = 200
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: maximumHeight)
                    .accessibilityLabel("将要发送的固定画面，请核对内容")
            }
        }
        .task(id: frame.id) {
            // Decode once per fixed frame, not on every streamed text update.
            image = nil
            guard let source = CGImageSourceCreateWithData(frame.jpegData as CFData,
                    [kCGImageSourceShouldCache: false] as CFDictionary),
                  let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 480
                  ] as CFDictionary) else { return }
            image = UIImage(cgImage: thumbnail)
        }
    }
}

/// Resolves this view's actual UIWindow; other app-owned windows cannot create a
/// false foreground-focus boundary. This reader creates no additional window.
private struct InteractionWindowReader: UIViewRepresentable {
    var onWindow: (UIWindow) -> Void

    func makeUIView(context: Context) -> WindowReaderView {
        let view = WindowReaderView()
        view.onWindow = onWindow
        return view
    }

    func updateUIView(_ view: WindowReaderView, context: Context) { view.onWindow = onWindow }

    final class WindowReaderView: UIView {
        var onWindow: ((UIWindow) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { onWindow?(window) }
        }
    }
}
