import AppIntents
import SwiftUI

/// A glanceable result, with no automatic keyboard, image gallery, or request in the view lifecycle.
struct QuickReadCardView: View {
    let requestID: String
    let result: QuickReadResult?
    let failure: String?
    let expanded: Bool
    let isPreview: Bool

    private var text: String {
        if isPreview {
            return "“Stay curious” 的意思是“保持好奇心”。它鼓励你遇到不懂的内容时继续探索，不急于接受现成结论。\n\n看懂后划走这张卡片，接着读原来的内容。"
        }
        return result?.answer.text ?? failure ?? "这张卡片已过期或已被新的截图替换。请回到要看的页面重新长按。"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(isPreview ? "原地阅读 · 示例" : "读懂眼前内容", systemImage: "text.viewfinder")
                    .font(.headline)
                Spacer(minLength: 8)
                if let result {
                    Text(result.snapshot.frame.timestamp, style: .time)
                        .font(.caption).foregroundStyle(.secondary)
                        .accessibilityLabel("截图接收时间")
                        .accessibilityValue(result.snapshot.frame.timestamp.formatted(date: .omitted, time: .shortened))
                }
            }
            Text(verbatim: String(text.prefix(expanded ? 1200 : 280)))
                .font(.body).lineLimit(expanded ? 16 : 7)
            if isPreview {
                Text("示例内容 · 未截图、未联网")
                    .font(.caption).foregroundStyle(.secondary)
            } else if let answer = result?.answer {
                if !answer.complete {
                    Label("回答未完成", systemImage: "exclamationmark.circle")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if answer.searchState != .notUsed {
                    Text(answer.searchState.label).font(.caption).foregroundStyle(.secondary)
                }
                ForEach(Array(answer.citations.prefix(expanded ? 4 : 1))) { citation in
                    Link(destination: citation.url) {
                        Label(citation.title, systemImage: "link").font(.caption).lineLimit(1)
                    }
                }
                if expanded {
                    Text("卡片最多显示部分内容，完整回答可在 App 中查看。")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
            if result != nil || isPreview {
                HStack {
                    Button(intent: ExpandQuickReadCardIntent(requestID: requestID, expanded: !expanded)) {
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel(expanded ? "收起" : "多看一些")
                    .help(expanded ? "收起" : "多看一些，不重新请求")
                    Spacer()
                    if result != nil {
                        Button(intent: ContinueQuickReadInAppIntent(requestID: requestID)) {
                            Label("在 App 中追问", systemImage: "arrow.up.forward.app")
                        }
                        .help("打开助手并接续此图和回答，可能全屏")
                    }
                }
            }
        }.padding(16)
    }
}
