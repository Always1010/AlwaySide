import SwiftUI
import UIKit

/// A small block renderer: streaming fragments remain readable, and unclosed code fences stay code.
struct MarkdownAnswerView: View {
    let text: String

    static func safeURL(_ url: URL) -> Bool {
        ["https", "http"].contains(url.scheme?.lowercased() ?? "") &&
        !(url.host ?? "").isEmpty && url.user == nil && url.password == nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case .paragraph(let value): inline(value).textSelection(.enabled)
                case .heading(let level, let value):
                    inline(value).font(level == 1 ? .title2.bold() : level == 2 ? .title3.bold() : .headline)
                        .textSelection(.enabled).accessibilityAddTraits(.isHeader)
                case .item(let marker, let value):
                    HStack(alignment: .top, spacing: 9) {
                        Text(marker).foregroundStyle(.secondary)
                        inline(value).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                    }
                case .quote(let value):
                    HStack(alignment: .top, spacing: 10) {
                        RoundedRectangle(cornerRadius: 2).fill(.secondary.opacity(0.4)).frame(width: 3)
                        inline(value).foregroundStyle(.secondary).textSelection(.enabled)
                    }.fixedSize(horizontal: false, vertical: true)
                case .code(let language, let value):
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(language.isEmpty ? "代码" : language).font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Button { UIPasteboard.general.string = value } label: { Image(systemName: "doc.on.doc") }
                                .help("复制代码").accessibilityLabel("复制代码")
                        }
                        ScrollView(.horizontal) {
                            Text(verbatim: value).font(.system(.callout, design: .monospaced))
                                .textSelection(.enabled).fixedSize(horizontal: true, vertical: false)
                        }
                    }.padding(12).background(Color(.tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
                case .table(let rows):
                    ScrollView(.horizontal) {
                        Grid(alignment: .topLeading, horizontalSpacing: 0, verticalSpacing: 0) {
                            ForEach(Array(rows.enumerated()), id: \.offset) { rowIndex, row in
                                GridRow {
                                    ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                                        inline(cell).font(rowIndex == 0 ? .callout.bold() : .callout)
                                            .textSelection(.enabled).frame(width: 170, alignment: .leading).padding(8)
                                            .background(rowIndex == 0 ? Color(.tertiarySystemFill) : Color.clear)
                                            .overlay(Rectangle().stroke(Color.secondary.opacity(0.2), lineWidth: 0.5))
                                    }
                                }
                            }
                        }.padding(1)
                    }
                case .rule: Divider()
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .environment(\.openURL, OpenURLAction { url in Self.safeURL(url) ? .systemAction : .discarded })
    }

    private func inline(_ value: String) -> Text {
        guard var attributed = try? AttributedString(markdown: value,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)) else { return Text(verbatim: value) }
        // Markdown may contain custom schemes. Strip them before the text can become interactive.
        let unsafe = attributed.runs.compactMap { run in
            if let url = run.link, !Self.safeURL(url) { return run.range }
            return nil
        }
        for range in unsafe { attributed[range].link = nil }
        return Text(attributed)
    }

    private enum Block {
        case paragraph(String), heading(Int, String), item(String, String), quote(String), code(String, String), table([[String]]), rule
    }
    private var blocks: [Block] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var result: [Block] = []
        var paragraph: [String] = []
        var code: [String] = []
        var fence: String?
        var language = ""
        func flush() {
            if !paragraph.isEmpty { result.append(.paragraph(paragraph.joined(separator: "\n"))); paragraph = [] }
        }
        var lineIndex = 0
        while lineIndex < lines.count {
            let line = lines[lineIndex]
            defer { lineIndex += 1 }
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if let active = fence {
                if trimmed.hasPrefix(active), trimmed.dropFirst(active.count).allSatisfy({ $0 == "`" || $0 == "~" || $0.isWhitespace }) {
                    result.append(.code(language, code.joined(separator: "\n"))); code = []; fence = nil
                } else { code.append(line) }
                continue
            }
            if lineIndex + 1 < lines.count, trimmed.contains("|"), isTableDivider(lines[lineIndex + 1]) {
                flush()
                var rows = [tableCells(trimmed)]
                lineIndex += 1
                while lineIndex + 1 < lines.count, lines[lineIndex + 1].contains("|"),
                      !lines[lineIndex + 1].trimmingCharacters(in: .whitespaces).isEmpty {
                    lineIndex += 1
                    rows.append(tableCells(lines[lineIndex]))
                }
                let columns = rows.map(\.count).max() ?? 0
                if columns > 0 {
                    rows = rows.map { $0 + Array(repeating: "", count: columns - $0.count) }
                    result.append(.table(rows))
                }
            } else if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                flush()
                let character = trimmed.first!
                let prefix = String(trimmed.prefix(while: { $0 == character }))
                fence = prefix
                language = String(trimmed.dropFirst(prefix.count).prefix(50))
            } else if trimmed.isEmpty { flush() }
            else if ["---", "***", "___"].contains(trimmed) { flush(); result.append(.rule) }
            else if trimmed.hasPrefix(">") {
                flush(); result.append(.quote(String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces)))
            } else {
                let hashes = trimmed.prefix(while: { $0 == "#" }).count
                if (1...6).contains(hashes), trimmed.dropFirst(hashes).first == " " {
                    flush(); result.append(.heading(hashes, String(trimmed.dropFirst(hashes + 1))))
                } else if let item = listItem(trimmed) {
                    flush(); result.append(.item(item.0, item.1))
                } else { paragraph.append(line) }
            }
        }
        flush()
        if fence != nil { result.append(.code(language, code.joined(separator: "\n"))) }
        return result
    }

    private func tableCells(_ line: String) -> [String] {
        var value = line.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("|") { value.removeFirst() }
        if value.hasSuffix("|") { value.removeLast() }
        return value.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }
    private func isTableDivider(_ line: String) -> Bool {
        let cells = tableCells(line)
        return !cells.isEmpty && cells.allSatisfy { cell in
            let value = cell.replacingOccurrences(of: ":", with: "").trimmingCharacters(in: .whitespaces)
            return value.count >= 3 && value.allSatisfy { $0 == "-" }
        }
    }

    private func listItem(_ line: String) -> (String, String)? {
        for marker in ["- ", "* ", "+ "] where line.hasPrefix(marker) {
            return ("•", String(line.dropFirst(2)))
        }
        let digits = line.prefix(while: { $0.isNumber })
        guard !digits.isEmpty, digits.count < 5 else { return nil }
        let rest = line.dropFirst(digits.count)
        guard rest.hasPrefix(". ") || rest.hasPrefix(") ") else { return nil }
        return ("\(digits).", String(rest.dropFirst(2)))
    }
}
