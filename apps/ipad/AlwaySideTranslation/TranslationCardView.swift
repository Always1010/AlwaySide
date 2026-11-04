import SwiftUI
import TranslationUIProvider
import UIKit

@MainActor
struct TranslationCardView: View {
    let context: any TranslationUIProviderContext
    @StateObject private var session = TranslationSession()
    @State private var mode: TranslationMode = .translation
    @State private var showingOriginal = false
    @State private var copied = false

    private var sourceText: String { context.inputText.map { String($0.characters) } ?? "" }
    private var result: String { session.text(for: mode) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Label("AlwaySide", systemImage: "character.bubble")
                    .font(.headline).lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 8)
                Button { context.expandSheet() } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }
                    .help("展开翻译卡片").accessibilityLabel("展开翻译卡片")
                Button {
                    session.cancel()
                    context.finish(translation: nil)
                } label: { Image(systemName: "xmark") }
                    .help("关闭").accessibilityLabel("关闭翻译")
            }
            Picker("结果类型", selection: $mode) {
                ForEach(TranslationMode.allCases) { value in Text(value.rawValue).tag(value) }
            }.pickerStyle(.segmented)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("自动识别 → 简体中文").font(.caption).foregroundStyle(.secondary)
                    DisclosureGroup(isExpanded: $showingOriginal) {
                        Text(verbatim: sourceText).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
                    } label: {
                        Text(verbatim: sourceText).lineLimit(showingOriginal ? 1 : 2)
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    .padding(12)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                    if !result.isEmpty {
                        MarkdownAnswerView(text: result)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                    } else {
                        Text(sourceText.isEmpty ? "没有收到所选文字。" : "正在准备翻译；失败后可点击下方按钮重试。")
                            .foregroundStyle(.secondary)
                    }
                    if session.isRunning {
                        HStack {
                            ProgressView()
                            Text("\(session.runningMode?.rawValue ?? "")：\(session.status)")
                                .font(.callout).foregroundStyle(.secondary)
                        }.accessibilityElement(children: .combine)
                    }
                    if let message = session.message {
                        Text(message).font(.callout).foregroundStyle(.secondary)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 14) {
                if session.isRunning {
                    Button { session.cancelWithMessage() } label: { Image(systemName: "stop.circle") }
                        .help("取消请求").accessibilityLabel("取消请求")
                } else {
                    Button {
                        copied = false
                        session.submit(source: sourceText, mode: mode)
                    } label: {
                        Label(result.isEmpty ? (mode == .translation ? "翻译" : "解释") : "重新生成", systemImage: "arrow.clockwise")
                    }.buttonStyle(.borderedProminent).disabled(sourceText.isEmpty)
                }
                Spacer(minLength: 0)
                if mode == .translation && context.allowsReplacement {
                    Button {
                        session.cancel()
                        context.finish(translation: AttributedString(session.translation))
                    } label: { Image(systemName: "arrow.left.arrow.right") }
                        .disabled(!session.canReplace || session.isRunning)
                        .help("用译文替换原文").accessibilityLabel("用译文替换原文")
                }
                Button {
                    UIPasteboard.general.string = result
                    copied = true
                } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc") }
                    .disabled(result.isEmpty)
                    .help(copied ? "已复制" : "复制当前结果")
                    .accessibilityLabel(copied ? "已复制" : "复制当前结果")
            }
            Text("选择翻译或解释会将原文发送至 OpenAI；模型和参数沿用主 App。")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .buttonStyle(.borderless)
        .task(id: sourceText) {
            guard session.source != sourceText else { return }
            session.reset(source: sourceText); copied = false
            mode = .translation
            if !sourceText.isEmpty { session.submit(source: sourceText, mode: .translation) }
        }
        .onChange(of: mode) { _, value in
            copied = false
            if !session.completedModes.contains(value), session.runningMode != value, !sourceText.isEmpty {
                session.submit(source: sourceText, mode: value)
            }
        }
        .onDisappear { session.cancel() }
    }
}
