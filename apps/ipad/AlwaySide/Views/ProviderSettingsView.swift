import SwiftUI

struct ProviderSettingsView: View {
    @ObservedObject var model: CompanionModel
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("\(model.provider.name) · \(model.provider.model)") {
                    SecureField("输入 API Key（不会显示已保存的密钥）", text: $key)
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text(model.keyAvailable ? "这家供应商的密钥已保存在本机。输入新密钥可替换。" : "还没有保存这家供应商的密钥。")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("保存到本机钥匙串") {
                        do {
                            try model.credentials.save(key, for: model.provider)
                            key = ""
                            model.refreshKeyStatus()
                            message = "已保存。没有发起网络验证或 API 请求。"
                        } catch {
                            message = (error as? ClientError ?? .keychain).localizedDescription
                        }
                    }.disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button("删除此供应商密钥", role: .destructive) {
                        do {
                            try model.credentials.delete(model.provider)
                            key = ""
                            model.refreshKeyStatus()
                            message = "已删除本机保存的密钥。"
                        } catch { message = ClientError.keychain.localizedDescription }
                    }.disabled(!model.keyAvailable)
                    if let message { Text(message).font(.footnote) }
                }
                Section("数据与用量") {
                    Text("密钥仅由你在此设备配置，保存在本机 Keychain，不同步 iCloud。应用直接连接所选供应商的官方 HTTPS API，无自建服务器。")
                    Text("只在你确认画面并点击提问时，发送一张图片、问题及有限文字追问。供应商按你的 API 账户规则计量；本应用不会自动重试或切换供应商。")
                    Text("本机清空内容不等于删除供应商已接收的数据。OpenAI 请求设置 store=false；供应商的数据保留仍以其账户政策为准。")
                }
            }
            .navigationTitle("本机密钥")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { key = ""; dismiss() } label: { Image(systemName: "checkmark") }
                        .help("完成")
                        .accessibilityLabel("完成密钥配置")
                }
            }
            .onDisappear { key = "" }
        }
    }
}
