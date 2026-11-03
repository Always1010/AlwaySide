import ImageIO
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
                if phase == .active { model.rememberReturnFrames() }
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
            Text("从共享的最近画面中选一张，或导入截图，核对并确认后再提问。")
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
                Button {
                    cancelPhotoImport()
                    model.discardEvidence()
                } label: { Image(systemName: "trash") }
                .help("清空画面、候选和追问，保留共享")
                .accessibilityLabel("清空画面、候选和追问")
                .disabled(model.snapshot == nil && model.frameCandidates.isEmpty && capture.latestFrame == nil)
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
                Text("回答和追问都基于上方已固定的画面；选择另一张缩略图才会更换并清空追问。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Button { model.refreshFrameCandidates() } label: {
                Label("更新最近画面候选", systemImage: "camera.viewfinder")
            }
            .buttonStyle(.bordered)
            .disabled(capture.state != .capturing || capture.latestFrame == nil || model.isAsking || importing)
            candidateStrip
            Text("返回助手时保留一组最近画面，向右查看更早的画面并点选目标页。候选不会随实时预览滚动丢失；“更新”会重新取一组。时间只是本机接收时间，不能证明来源，请核对完整页面，避开桌面、切换器或助手自身。")
                .font(.footnote).foregroundStyle(.secondary)
            if let error = capture.lastError {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.footnote).foregroundStyle(.orange)
            }
        }
        .padding(20)
        .background(.background, in: RoundedRectangle(cornerRadius: 20))
    }

    @ViewBuilder private var candidateStrip: some View {
        if !model.frameCandidates.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("最近画面 · 点选一张以固定").font(.headline)
                Text("\(model.frameCandidates.count) 张候选 · 新到旧 · 横向滑动查看")
                    .font(.caption).foregroundStyle(.secondary)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(model.frameCandidates) { frame in
                            FrameCandidateThumbnail(frame: frame, selected: model.snapshot?.id == frame.id) {
                                model.chooseCandidate(frame)
                            }
                        }
                    }
                    .padding(4)
                }
                .disabled(importing)
                if model.snapshot == nil {
                    Text("尚未选择固定画面，不能发送。")
                        .font(.footnote).foregroundStyle(.secondary)
                } else {
                    Text("当前仍使用上方已固定的画面；点选缩略图才更换。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
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

private struct FrameCandidateThumbnail: View {
    let frame: CapturedFrame
    let selected: Bool
    let action: () -> Void
    @State private var thumbnail: UIImage?

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    Color(.secondarySystemGroupedBackground)
                    if let thumbnail {
                        Image(uiImage: thumbnail).resizable().scaledToFit()
                    } else {
                        Image(systemName: "photo").foregroundStyle(.secondary)
                    }
                }
                .frame(width: 144, height: 112)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(selected ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: selected ? 3 : 1)
                }
                Text("接收于 \(frame.timestamp.formatted(date: .omitted, time: .standard))")
                    .font(.caption2.monospacedDigit())
                Text(selected ? "已固定" : "选择这张").font(.caption)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("选择接收于 \(frame.timestamp.formatted(date: .omitted, time: .standard)) 的画面")
        .accessibilityValue(selected ? "已固定" : "未选择")
        .help("选择这张画面并清空旧追问")
        .onAppear { loadThumbnail() }
        .onDisappear { thumbnail = nil }
    }

    private func loadThumbnail() {
        guard thumbnail == nil,
              let source = CGImageSourceCreateWithData(frame.jpegData as CFData,
                  [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: 240,
                  kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { return }
        thumbnail = UIImage(cgImage: image)
    }
}
