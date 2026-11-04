import CoreTransferable
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct CompanionView: View {
    @ObservedObject var model: CompanionModel
    @ObservedObject var capture: ScreenCaptureController
    @ObservedObject var listening: WindowListening
    @FocusState private var inputFocused: Bool
    @State private var showingSettings = false
    @State private var showingHistory = false
    @State private var showingPhotoPicker = false
    @State private var showingAudioImporter = false
    @State private var previewExpanded = false
    @State private var photoSelection: PhotosPickerItem?
    @State private var photoTask: Task<Void, Never>?
    @State private var photoRequestID: UUID?
    @State private var photoSessionID: UUID?
    @State private var importing = false

    private var busy: Bool {
        operationBusy || listening.blocksCapture || capture.blocksListening
    }

    private var operationBusy: Bool {
        model.isAsking || model.isAcquiringScreen || importing || listening.isBusy || capture.blocksListening
    }
    private var showingLiveAudio: Bool { listening.phase == .processing || listening.phase == .failed }
    private var replacingSavedAudio: Bool { showingLiveAudio && model.sessionID == listening.reading.id }
    private var visibleMessages: [ChatSessionStore.SavedTurn] {
        replacingSavedAudio ? Array(model.messages.dropFirst()) : model.messages
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                primaryActions
                activityStatus
                if model.snapshot != nil || model.audioTranscript != nil { evidenceSummary }
                Divider()
                results
                composer
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("AlwaySide")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { inputFocused = false; showingHistory = true } label: { Image(systemName: "clock.arrow.circlepath") }
                        .help("会话历史").accessibilityLabel("会话历史").disabled(busy)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { cancelPhotoImport(); model.startNewConversation() } label: { Image(systemName: "square.and.pencil") }
                        .help("新建对话").accessibilityLabel("新建对话").disabled(busy)
                    Menu {
                        Picker("画面范围", selection: $capture.source) {
                            ForEach(ScreenCaptureSource.allCases) { Text($0.title).tag($0) }
                        }.disabled(busy)
                        Button { showingPhotoPicker = true } label: { Label("导入图片", systemImage: "photo.badge.plus") }.disabled(busy)
                        Button { inputFocused = false; model.toggleListening(microphone: true) } label: { Label("用麦克风监听", systemImage: "mic") }
                            .disabled(busy || model.provider != .openAI)
                        Button { showingAudioImporter = true } label: { Label("导入音频并解释", systemImage: "waveform.badge.plus") }
                            .disabled(busy || model.provider != .openAI)
                        Button { showingSettings = true } label: { Label("设置", systemImage: "slider.horizontal.3") }
                            .disabled(operationBusy || listening.recorder.isRecording)
                    } label: { Image(systemName: "ellipsis.circle") }
                        .help("画面范围、导入与设置").accessibilityLabel("更多操作与设置")
                }
            }
            .sheet(isPresented: $showingSettings) { ProviderSettingsView(model: model) }
            .sheet(isPresented: $showingHistory) { ChatHistoryView(model: model) }
            .photosPicker(isPresented: $showingPhotoPicker, selection: $photoSelection, matching: .images)
            .fileImporter(isPresented: $showingAudioImporter, allowedContentTypes: [.audio]) { result in
                if case .success(let url) = result { model.processAudioFile(url) }
            }
            .onChange(of: photoSelection) { _, selection in importPhoto(selection) }
            .onChange(of: model.sessionID) { _, id in
                if let photoSessionID, photoSessionID != id { cancelPhotoImport() }
                previewExpanded = false
            }
            .onDisappear { cancelPhotoImport(); model.cancelScreenAcquisition() }
        }
    }

    private var primaryActions: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { captureButton; listeningButton }
            VStack(spacing: 10) { captureButton; listeningButton }
        }.padding(12)
    }

    private var captureButton: some View {
        Button {
            inputFocused = false
            model.acquireScreen()
        } label: {
            Label(model.isAcquiringScreen ? "正在获取…" : "获取当前界面", systemImage: "camera.viewfinder")
                .font(.headline).padding(.vertical, 14).frame(minWidth: 144, maxWidth: .infinity, minHeight: 38)
        }
        .buttonStyle(.borderedProminent)
        .help("获取系统共享画面并上传分析").accessibilityLabel("获取当前界面并分析")
        .disabled(busy)
    }

    private var listeningButton: some View {
        Button { inputFocused = false; model.toggleListening() } label: {
            Label(listeningTitle, systemImage: listening.hasPendingRecording ? "stop.fill" : "waveform")
                .font(.headline).padding(.vertical, 14).frame(minWidth: 144, maxWidth: .infinity, minHeight: 38)
        }
        .buttonStyle(.borderedProminent)
        .tint(listening.hasPendingRecording ? .red : .accentColor)
        .help("开始收录设备音频；结束时上传并翻译解释").accessibilityLabel(listeningTitle)
        .disabled(operationBusy || model.provider != .openAI)
    }

    private var listeningTitle: String {
        switch listening.phase {
        case .requesting: "等待授权…"
        case .processing: "正在处理…"
        case .recording: "结束监听"
        case .ready: "解释本段"
        case .failed: listening.hasPendingRecording ? "重试解释" : "开始监听"
        case .idle: "开始监听"
        }
    }

    private var activityStatus: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.isAcquiringScreen {
                HStack {
                    ProgressView().controlSize(.small)
                    Text(capture.state.title).font(.caption)
                    Spacer()
                    Button { model.cancelScreenAcquisition() } label: { Image(systemName: "xmark.circle") }
                        .help("取消获取画面").accessibilityLabel("取消获取画面")
                }
            } else if capture.blocksListening {
                HStack {
                    Text(capture.lastError ?? "正在结束画面共享…").font(.caption)
                    Spacer()
                    if capture.state == .failed {
                        Button { Task { await capture.stopCapture() } } label: { Image(systemName: "stop.circle") }
                            .help("重试停止画面共享").accessibilityLabel("重试停止画面共享")
                    } else { ProgressView().controlSize(.small) }
                }
            } else if listening.phase == .recording || listening.phase == .requesting || listening.phase == .ready || listening.isBusy {
                HStack {
                    if listening.isBusy { ProgressView().controlSize(.small) }
                    Text(listeningStatus).font(.caption).monospacedDigit()
                    Spacer(minLength: 4)
                    Button {
                        if listening.phase == .processing { listening.cancelProcessing() }
                        else { Task { await listening.discard() } }
                    } label: { Image(systemName: "xmark.circle") }
                        .help("取消当前监听或处理").accessibilityLabel("取消当前监听或处理")
                }
            }
            if let failure = listening.failure {
                Text(failure).font(.caption).foregroundStyle(.orange)
                if listening.phase == .failed, listening.hasPendingRecording {
                    Button { Task { await listening.discard() } } label: { Label("放弃本段", systemImage: "trash") }
                        .font(.caption)
                }
            }
            if !model.keyAvailable {
                Button { showingSettings = true } label: { Label("设置 API Key", systemImage: "key.horizontal") }
                    .font(.caption).disabled(operationBusy || listening.recorder.isRecording)
            }
            if model.provider == .openAI, let validation = model.openAISettings.validationMessage {
                Text(validation).font(.caption).foregroundStyle(.orange)
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.bottom, 6)
    }

    private var listeningStatus: String {
        switch listening.phase {
        case .requesting: "请在系统窗口授权音频来源"
        case .recording: "正在监听 · \(Int(listening.recorder.elapsedSeconds)) 秒 / 120 秒"
        case .ready: "本段已停止，点击「解释本段」上传处理"
        case .processing: listening.reading.phase == .transcribing ? "正在转写原文…" : "正在翻译和解释…"
        case .failed: "本段未完成"
        case .idle: "正在结束本段…"
        }
    }

    private var results: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if model.messages.isEmpty && !model.isAsking && (!showingLiveAudio || listening.reading.answer.isEmpty) {
                        VStack(alignment: .leading, spacing: 10) {
                            Image(systemName: "text.bubble").font(.largeTitle).foregroundStyle(.tint)
                            Text("看一眼，或听一段").font(.headline)
                            Text("将此窗口放在旁边。获取当前界面，或开始监听；翻译和解释会显示在这里。")
                                .font(.callout).foregroundStyle(.secondary)
                        }.padding(.vertical, 20)
                    }
                    ForEach(Array(visibleMessages.enumerated()), id: \.offset) { _, message in messageView(message) }
                    if model.isAsking {
                        userBubble(model.pendingQuestion)
                        if !model.answer.isEmpty { MarkdownAnswerView(text: model.answer) }
                    }
                    if showingLiveAudio {
                        if !listening.reading.transcript.isEmpty {
                            DisclosureGroup("本段原文") { Text(verbatim: listening.reading.transcript).textSelection(.enabled) }
                        }
                        if !listening.reading.answer.isEmpty { MarkdownAnswerView(text: listening.reading.answer) }
                    }
                    if let warning = model.storageWarning { Text(warning).font(.footnote).foregroundStyle(.orange) }
                    if let notice = model.notice {
                        Text(notice).font(.footnote).foregroundStyle(.secondary).accessibilityAddTraits(.updatesFrequently)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: model.messages.count) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            .onChange(of: model.isAsking) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    private var evidenceSummary: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let snapshot = model.snapshot {
                Button { previewExpanded.toggle() } label: {
                    HStack {
                        Image(systemName: "photo")
                        Text("当前画面").font(.caption)
                        Text(snapshot.frame.timestamp.formatted(date: .omitted, time: .shortened)).font(.caption2).foregroundStyle(.secondary)
                        Spacer()
                        Image(systemName: previewExpanded ? "chevron.up" : "chevron.down")
                    }
                }.buttonStyle(.plain).help("展开或收起当前画面").accessibilityLabel("展开或收起当前画面")
                if previewExpanded { EvidencePreview(frame: snapshot.frame, maximumHeight: 140) }
            }
            if let transcript = model.audioTranscript, !replacingSavedAudio {
                DisclosureGroup("本段原文 · \(transcript.count) 字") {
                    Text(verbatim: transcript).font(.callout).textSelection(.enabled)
                }.font(.caption)
            }
        }.padding(.horizontal, 14).padding(.vertical, 8)
    }

    private func userBubble(_ text: String) -> some View {
        HStack {
            Spacer(minLength: 20)
            Text(verbatim: text).textSelection(.enabled).padding(10)
                .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func messageView(_ message: ChatSessionStore.SavedTurn) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            userBubble(message.question)
            MarkdownAnswerView(text: message.answer)
            if !message.complete {
                Text("回答未完成 · 未加入追问上下文").font(.caption).foregroundStyle(.secondary)
            } else if message.turn.searchState != .notUsed {
                Text(message.turn.searchState.label).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(message.turn.citations) { citation in
                Link(destination: citation.url) {
                    Label(citation.title.isEmpty ? citation.url.host ?? "查看来源" : citation.title, systemImage: "link")
                        .font(.callout).lineLimit(2)
                }
            }
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let status = model.requestStatus {
                HStack { ProgressView().controlSize(.mini); Text(status.label).font(.caption) }
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField("继续追问…", text: $model.question, axis: .vertical)
                    .lineLimit(1...4).textFieldStyle(.roundedBorder).focused($inputFocused)
                    .disabled(busy)
                    .onChange(of: model.question) { _, text in
                        if text.count > 2000 { model.question = String(text.prefix(2000)) }
                    }
                if model.isAsking {
                    Button { model.cancelQuestion() } label: { Image(systemName: "stop.circle.fill").font(.title) }
                        .help("停止回答").accessibilityLabel("停止回答")
                } else {
                    Button { inputFocused = false; model.ask() } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }
                        .help("发送问题").accessibilityLabel("发送问题").disabled(!model.canAsk || busy)
                }
            }
            Text("获取画面或结束监听会上传本次内容；结果保存在本机。")
                .font(.caption2).foregroundStyle(.secondary)
        }.padding(12).background(.background)
    }

    private func importPhoto(_ selection: PhotosPickerItem?) {
        guard let selection, !busy else { return }
        cancelPhotoImport()
        model.startNewConversation()
        let id = UUID()
        let session = model.sessionID
        photoRequestID = id; photoSessionID = session; importing = true
        photoTask = Task { @MainActor in
            defer { if photoRequestID == id { cancelPhotoImport() } }
            do {
                guard let imported = try await selection.loadTransferable(type: ImportedPhoto.self) else { throw ClientError.invalidImage }
                try Task.checkCancellation()
                guard photoRequestID == id, model.sessionID == session else { return }
                photoRequestID = nil; photoSessionID = nil; importing = false; photoTask = nil; photoSelection = nil
                model.choose(imported.snapshot)
                previewExpanded = true
                model.ask()
            } catch {
                if photoRequestID == id, model.sessionID == session, !Task.isCancelled { model.reportPhotoError() }
            }
        }
    }

    private func cancelPhotoImport() {
        photoRequestID = nil; photoSessionID = nil; photoTask?.cancel(); photoTask = nil
        importing = false; photoSelection = nil
    }
}

/// Finish reading the temporary transfer file inside the importing closure, before its lifetime ends.
private struct ImportedPhoto: Transferable, Sendable {
    let snapshot: EvidenceSnapshot

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            let url = received.file
            let decoding = Task.detached(priority: .userInitiated) {
                try Task.checkCancellation()
                return try PhotoImport.snapshot(from: url)
            }
            return try await withTaskCancellationHandler {
                let snapshot = try await decoding.value
                try Task.checkCancellation()
                return ImportedPhoto(snapshot: snapshot)
            } onCancel: {
                decoding.cancel()
            }
        }
    }
}

private struct ChatHistoryView: View {
    @ObservedObject var model: CompanionModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [ChatSessionSummary] = []
    @State private var showingDeleteAll = false

    private struct SearchRequest: Equatable {
        let query: String
        let summaries: [ChatSessionSummary]
    }
    private var searchRequest: SearchRequest { .init(query: query, summaries: model.sessionSummaries) }
    private var visibleResults: [ChatSessionSummary] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return model.sessionSummaries }
        let matchedIDs = Set(results.map(\.id))
        // A deletion takes effect immediately, even while the replacement search is debouncing.
        return model.sessionSummaries.filter { matchedIDs.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            List {
                if visibleResults.isEmpty { Text(query.isEmpty ? "还没有本机会话" : "没有找到相关会话").foregroundStyle(.secondary) }
                ForEach(visibleResults) { session in
                    Button { model.openSession(session.id); dismiss() } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(session.title).foregroundStyle(.primary).lineLimit(2)
                            Text(session.preview).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                            Text(session.updatedAt.formatted(date: .abbreviated, time: .shortened)).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions { Button(role: .destructive) { model.deleteSession(session.id) } label: { Label("删除", systemImage: "trash") } }
                }
                Section {
                    Text("最多保存 30 段会话、每段最多 60 轮和 1 张图片。达到会话上限请先删除旧会话，达到轮数上限请新建对话；不自动删除。搜索涵盖已保存的问题与回答。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .searchable(text: $query, prompt: "搜索问题与回答")
            .navigationTitle("会话历史").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(role: .destructive) { showingDeleteAll = true } label: { Image(systemName: "trash") }
                        .help("删除全部本机聊天与图片").accessibilityLabel("删除全部本机会话").disabled(model.sessionSummaries.isEmpty)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "checkmark") }.help("关闭历史").accessibilityLabel("关闭历史")
                }
            }
            .confirmationDialog("删除全部本机聊天与图片？", isPresented: $showingDeleteAll, titleVisibility: .visible) {
                Button("删除全部", role: .destructive) { model.deleteAllSessions() }
            } message: { Text("无法恢复；不会删除 Keychain 密钥，也无法撤回供应商已接收的数据。") }
            .task(id: searchRequest) {
                let request = searchRequest
                if !request.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                }
                let matches = await model.searchSessions(request.query)
                guard !Task.isCancelled, request == searchRequest else { return }
                results = matches
            }
        }
    }
}

private struct EvidencePreview: View {
    let frame: CapturedFrame
    var maximumHeight: CGFloat = 200
    @State private var image: UIImage?
    @State private var decodedFrameID: UUID?

    var body: some View {
        ZStack {
            Color(.secondarySystemGroupedBackground)
            if decodedFrameID == frame.id, let image {
                Image(uiImage: image).resizable().scaledToFit().accessibilityLabel("当前会话图片")
            } else if decodedFrameID == frame.id {
                Image(systemName: "exclamationmark.triangle").accessibilityLabel("图片预览失败，请重新导入")
            } else { ProgressView().controlSize(.small) }
        }
        .frame(maxWidth: .infinity).frame(height: maximumHeight).clipShape(RoundedRectangle(cornerRadius: 6))
        .task(id: frame.id) {
            guard decodedFrameID != frame.id else { return }
            guard let source = CGImageSourceCreateWithData(frame.jpegData as CFData, nil),
                  let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 480
                  ] as CFDictionary) else { decodedFrameID = frame.id; return }
            guard !Task.isCancelled else { return }
            image = UIImage(cgImage: thumbnail); decodedFrameID = frame.id
        }
    }
}
