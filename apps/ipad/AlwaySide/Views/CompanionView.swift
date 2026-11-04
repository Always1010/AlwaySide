import CoreTransferable
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct CompanionView: View {
    @ObservedObject var model: CompanionModel
    @ObservedObject var capture: ScreenCaptureController
    @ObservedObject private var recovery = WindowRecovery.shared
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var inputFocused: Bool
    @State private var showingSettings = false
    @State private var showingHistory = false
    @State private var showingAudio = false
    @State private var showingScreenshotHelp = false
    @State private var showingPhotoPicker = false
    @State private var previewExpanded = false
    @State private var photoSelection: PhotosPickerItem?
    @State private var photoTask: Task<Void, Never>?
    @State private var photoRequestID: UUID?
    @State private var photoSessionID: UUID?
    @State private var importing = false
    @State private var editingRecoveryTask: Task<Void, Never>?
    @State private var editingRecoveryID: UUID?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let status = recovery.status {
                    HStack {
                        Text(status).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button { recovery.dismissStatus() } label: { Image(systemName: "xmark") }
                            .help("关闭窗口恢复提示").accessibilityLabel("关闭窗口恢复提示")
                    }.padding(10)
                }
                if showingAudio {
                    AudioCompanionView(model: model, onClose: { showingAudio = false }, onFollowUp: { result in
                        model.acceptAudioRead(result)
                        showingAudio = false
                    })
                } else {
                    evidenceSummary
                    Divider()
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 18) {
                                if model.messages.isEmpty && !model.isAsking {
                                    ContentUnavailableView {
                                        Label("看懂，然后继续", systemImage: "text.viewfinder")
                                    } description: {
                                        Text("长按截屏，在原页面的系统卡片里读懂内容。需要深入讨论时，再来这里追问。")
                                    } actions: {
                                        Button { showingScreenshotHelp = true } label: {
                                            Label("设置长按阅读", systemImage: "hand.tap")
                                        }
                                    }
                                }
                                ForEach(Array(model.messages.enumerated()), id: \.offset) { _, message in
                                    messageView(message)
                                }
                                if model.isAsking {
                                    userBubble(model.pendingQuestion)
                                    if !model.answer.isEmpty { MarkdownAnswerView(text: model.answer) }
                                }
                                if let warning = model.storageWarning {
                                    Text(warning).font(.footnote).foregroundStyle(.orange)
                                }
                                if let notice = model.notice {
                                    Text(notice).font(.footnote).foregroundStyle(.secondary)
                                        .accessibilityAddTraits(.updatesFrequently)
                                }
                                Color.clear.frame(height: 1).id("bottom")
                            }.padding(16)
                        }
                        .defaultScrollAnchor(.bottom)
                        .onChange(of: model.messages.count) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                        .onChange(of: model.isAsking) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                    }
                    composer
                }
            }
            .background(Color(.systemGroupedBackground))
            .background(InteractionWindowReader { model.bindInteractionWindow($0) }.frame(width: 0, height: 0))
            .navigationTitle("AlwaySide")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    Button { showingHistory = true } label: { Image(systemName: "clock.arrow.circlepath") }
                        .help("查找与打开本机会话").accessibilityLabel("会话历史").disabled(showingAudio)
                    Button { resetInteraction(); model.startNewConversation() } label: { Image(systemName: "square.and.pencil") }
                        .help("新建对话").accessibilityLabel("新建对话").disabled(showingAudio)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { resetInteraction(); showingAudio = true } label: { Image(systemName: "waveform") }
                        .help("陪听：听一段，结束后自动翻译和解释").accessibilityLabel("陪听")
                        .disabled(showingAudio || model.isAsking || busyImport || model.provider != .openAI)
                    Menu {
                        Button { showingSettings = true } label: { Label("模型与密钥", systemImage: "slider.horizontal.3") }
                        Button { showingScreenshotHelp = true } label: { Label("长按截屏设置", systemImage: "camera.viewfinder") }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .help("设置与截屏入口").accessibilityLabel("设置与截屏入口")
                }
            }
            .sheet(isPresented: $showingSettings) { ProviderSettingsView(model: model) }
            .sheet(isPresented: $showingHistory) { ChatHistoryView(model: model) }
            .sheet(isPresented: $showingScreenshotHelp) { ScreenshotSetupView() }
            .photosPicker(isPresented: $showingPhotoPicker, selection: $photoSelection, matching: .images)
            .onChange(of: presentationActive) { _, active in
                if active { cancelEditingRecovery(); inputFocused = false }
                model.setInternalPresentation(active)
            }
            .onChange(of: photoSelection) { _, selection in importPhoto(selection) }
            .onChange(of: model.isAsking) { _, asking in if asking { cancelEditingRecovery() } }
            .onChange(of: model.sessionID) { _, sessionID in
                // A transfer belongs to its destination conversation, including while downloading from iCloud.
                if let photoSessionID, photoSessionID != sessionID { cancelPhotoImport() }
                previewExpanded = false
            }
            .onChange(of: scenePhase) { _, phase in if phase != .active { cancelEditingRecovery() } }
            .onDisappear { cancelEditingRecovery(); cancelPhotoImport() }
        }
    }

    private var presentationActive: Bool {
        showingSettings || showingHistory || showingAudio || showingScreenshotHelp || showingPhotoPicker
    }
    private var busyImport: Bool { importing || model.isImporting }

    private var evidenceSummary: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                if let snapshot = model.snapshot {
                    Button { previewExpanded.toggle() } label: {
                        EvidencePreview(frame: snapshot.frame, maximumHeight: 40).frame(width: 54, height: 40)
                    }.buttonStyle(.plain).help("展开或收起当前图片").accessibilityLabel("当前图片，展开预览")
                } else { Image(systemName: "photo").font(.title2).foregroundStyle(.secondary) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.snapshot == nil ? "导入截图" : "当前会话图片").font(.subheadline)
                    if let snapshot = model.snapshot {
                        Text("接收于 " + snapshot.frame.timestamp.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    } else {
                        Text(model.activeModelName).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if busyImport { ProgressView().controlSize(.small) }
                Button { cancelEditingRecovery(); showingPhotoPicker = true } label: { Image(systemName: "photo.badge.plus") }
                    .help("导入图片并新建对话").accessibilityLabel("导入图片并新建对话").disabled(busyImport)
            }
            if previewExpanded, let snapshot = model.snapshot {
                EvidencePreview(frame: snapshot.frame)
                Text(snapshot.origin + " · " + model.frameStatus).font(.caption).foregroundStyle(.secondary)
            }
            if let transcript = model.audioTranscript {
                DisclosureGroup {
                    Text(verbatim: transcript).font(.callout).textSelection(.enabled)
                    Button { model.removeAudioTranscript() } label: { Image(systemName: "xmark.circle") }
                        .help("移除音频转写并开始新上下文").accessibilityLabel("移除音频转写").disabled(model.isAsking)
                } label: {
                    Label("本段原文 · \(transcript.count) 字", systemImage: "waveform").font(.caption)
                }
            }
            if !model.keyAvailable {
                Button { showingSettings = true } label: { Label("保存 API Key", systemImage: "key.horizontal") }.font(.footnote)
            }
            if model.provider == .openAI, let validation = model.openAISettings.validationMessage {
                Label(validation, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.orange)
            }
        }.padding(.horizontal, 14).padding(.vertical, 10)
    }

    private func userBubble(_ text: String) -> some View {
        HStack { Spacer(minLength: 24)
            Text(verbatim: text).textSelection(.enabled).padding(12)
                .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func messageView(_ message: ChatSessionStore.SavedTurn) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            userBubble(message.question)
            MarkdownAnswerView(text: message.answer)
            if !message.complete {
                Label("回答未完成 · 未加入追问上下文", systemImage: "exclamationmark.circle")
                    .font(.caption).foregroundStyle(.secondary)
            } else if model.provider == .openAI && message.turn.searchState != .notUsed {
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
                Button { requestQuestionEditing() } label: { Image(systemName: "keyboard").font(.title2).frame(minWidth: 36, minHeight: 44) }
                .help("编辑问题与恢复键盘").accessibilityLabel("编辑问题与恢复键盘").disabled(model.isAsking || busyImport)
                TextField(model.audioTranscript == nil ? "输入问题；留空翻译当前图片" : "追问这段音频", text: $model.question, axis: .vertical)
                    .lineLimit(1...4).textFieldStyle(.roundedBorder).focused($inputFocused)
                    .disabled(model.isAsking || busyImport)
                    .onChange(of: model.question) { _, text in if text.count > 2000 { model.question = String(text.prefix(2000)) } }
                if model.isAsking {
                    Button { model.cancelQuestion() } label: { Image(systemName: "stop.circle.fill").font(.title) }
                        .help("停止回答").accessibilityLabel("停止回答")
                } else {
                    Button { cancelEditingRecovery(); inputFocused = false; model.ask() } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }
                        .help("发送问题；留空使用默认提问").accessibilityLabel("发送问题")
                        .disabled(!model.canAsk || busyImport)
                }
            }
            Text("发送才上传当前图、附加转写及最近 3 轮完整文字；聊天保存在本机。")
                .font(.caption2).foregroundStyle(.secondary)
        }.padding(12).background(.background)
    }

    private func importPhoto(_ selection: PhotosPickerItem?) {
        guard let selection else { return }
        resetInteraction()
        model.startNewConversation()
        let id = UUID()
        let destinationSession = model.sessionID
        photoRequestID = id; photoSessionID = destinationSession; importing = true
        photoTask = Task { @MainActor in
            defer {
                if photoRequestID == id {
                    importing = false; photoTask = nil; photoRequestID = nil; photoSessionID = nil; photoSelection = nil
                }
            }
            do {
                guard let imported = try await selection.loadTransferable(type: ImportedPhoto.self) else { throw ClientError.invalidImage }
                try Task.checkCancellation()
                guard photoRequestID == id, model.sessionID == destinationSession, !Task.isCancelled else { return }
                model.choose(imported.snapshot)
                previewExpanded = true
            } catch { if photoRequestID == id, model.sessionID == destinationSession, !Task.isCancelled { model.reportPhotoError() } }
        }
    }
    private func requestQuestionEditing() {
        cancelEditingRecovery()
        guard scenePhase == .active, !model.isAsking, !busyImport, !presentationActive else { return }
        previewExpanded = false; inputFocused = false
        guard let windowID = model.prepareExplicitQuestionEditing() else { return }
        let id = UUID(); editingRecoveryID = id
        editingRecoveryTask = Task { @MainActor in
            defer { if editingRecoveryID == id { editingRecoveryID = nil; editingRecoveryTask = nil } }
            do { try await Task.sleep(nanoseconds: 50_000_000) } catch { return }
            guard !Task.isCancelled, editingRecoveryID == id, scenePhase == .active,
                  !model.isAsking, !busyImport, !presentationActive,
                  model.activateExplicitQuestionEditing(in: windowID) else { return }
            inputFocused = true
        }
    }
    private func cancelEditingRecovery() { editingRecoveryID = nil; editingRecoveryTask?.cancel(); editingRecoveryTask = nil }
    private func resetInteraction() { cancelEditingRecovery(); cancelPhotoImport(); inputFocused = false }
    private func cancelPhotoImport() {
        photoRequestID = nil; photoSessionID = nil; photoTask?.cancel(); photoTask = nil; importing = false; photoSelection = nil
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

private struct InteractionWindowReader: UIViewRepresentable {
    var onWindow: (UIWindow) -> Void
    func makeUIView(context: Context) -> WindowReaderView { let view = WindowReaderView(); view.onWindow = onWindow; return view }
    func updateUIView(_ view: WindowReaderView, context: Context) { view.onWindow = onWindow }
    final class WindowReaderView: UIView {
        var onWindow: ((UIWindow) -> Void)?
        override func didMoveToWindow() { super.didMoveToWindow(); if let window { onWindow?(window) } }
    }
}
