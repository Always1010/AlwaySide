import SwiftUI
import UIKit

struct DiagnosticsView: View {
    private let report = DiagnosticsReport.current
    @State private var copied = false

    var body: some View {
        NavigationView {
            List {
                Section(header: Text("用途")) {
                    Text("验证这个项目能否在你的 iPad 上打开并运行，读取当前系统与构建信息。")
                    Text("这是待真机验收的最小探针，不执行屏幕或音频采集。")
                        .foregroundColor(.secondary)
                }

                Section(header: Text("设备系统")) {
                    ForEach(report.systemFields) { field in
                        fieldRow(field)
                    }
                }

                Section(header: Text("Bundle / SDK 构建信息")) {
                    ForEach(report.bundleFields) { field in
                        fieldRow(field)
                    }
                }

                Section(header: Text("编译期模块探测")) {
                    ForEach(report.moduleFields) { field in
                        fieldRow(field)
                    }
                }

                Section(header: Text("如何理解结果")) {
                    Text(DiagnosticsReport.explanation)
                    Text("不请求采集或后台权限，不读取设备唯一标识，不访问网络。复制按钮仅在你点击时写入系统剪贴板。")
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("iPad 运行诊断")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        UIPasteboard.general.string = report.text
                        copied = true
                    } label: {
                        Label("复制诊断文本", systemImage: "doc.on.doc")
                    }
                    .accessibilityLabel("复制诊断文本")
                    .accessibilityHint("将页面中的全部诊断结果复制到系统剪贴板")
                    .help("复制诊断文本")
                }
            }
            .alert("已复制诊断文本", isPresented: $copied) {
                Button("好", role: .cancel) {}
            } message: {
                Text("可以粘贴到聊天中回传结果。")
            }
        }
        .navigationViewStyle(.stack)
    }

    private func fieldRow(_ field: DiagnosticField) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(field.label)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Text(field.value)
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
