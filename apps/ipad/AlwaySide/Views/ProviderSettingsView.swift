import SwiftUI
import UIKit

struct ProviderSettingsView: View {
    @ObservedObject var model: CompanionModel
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var message: String?
    @State private var presetName = "我的指令"
    @State private var selectedPresetID: UUID?

    var body: some View {
        NavigationStack {
            Form {
                Section("供应商与本机密钥") {
                    Picker("供应商", selection: Binding(get: { model.provider }, set: { model.selectProvider($0) })) {
                        ForEach(ModelProvider.allCases) { Text($0.name).tag($0) }
                    }.disabled(model.isAsking)
                    SecureField("API Key", text: $key)
                        .textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text(model.keyAvailable ? "密钥已存本机；输入新值可替换。" : "只需填写 API Key，其余参数已有默认值。")
                        .font(.footnote).foregroundStyle(.secondary)
                    HStack {
                        Button { saveKey() } label: { Label("保存密钥", systemImage: "key.horizontal") }
                            .disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Spacer()
                        Button(role: .destructive) { deleteKey() } label: { Image(systemName: "trash") }
                            .help("删除此供应商的本机密钥")
                            .accessibilityLabel("删除此供应商的本机密钥")
                            .disabled(!model.keyAvailable)
                    }
                    if let message { Text(message).font(.footnote).foregroundStyle(.secondary) }
                }
                Section("默认提问") {
                    TextField("留空发送时使用的问题", text: $model.openAISettings.defaultQuestion, axis: .vertical)
                        .lineLimit(2...5)
                    Text("点击发送且输入框为空时使用，适用于两家供应商。唤起窗口不会自动发送。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if model.provider == .openAI {
                    openAIModelSection
                    instructionSection
                } else {
                    Section("DeepSeek") {
                        Text("deepseek-flash · 图片与文字单次接口")
                        Text("沿用当前 DeepSeek 请求方式；此处不提供 OpenAI 专用的推理、搜索与指令配置。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("数据与用量") {
                    Text("密钥仅存此设备 Keychain，不同步 iCloud。模型、指令与默认问题保存在本机；画面和聊天仅在当前进程内存中。")
                    Text("点击发送才将固定图片、问题与最多 3 轮文字发至所选供应商。使用你的 API 账户用量，不自动重试或更换供应商。OpenAI 搜索也可能产生工具用量。")
                    Text("请求开始时固定配置；正在进行的回答不随此处编辑变化。OpenAI 使用 store=false；清空本机数据无法撤回已发数据，实际保留规则由供应商账户政策决定。")
                }.font(.footnote)
            }
            .navigationTitle("助手设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { key = ""; dismiss() } label: { Image(systemName: "checkmark") }
                        .help("完成，设置已自动保存在本机")
                        .accessibilityLabel("完成设置")
                }
            }
            .onChange(of: model.provider) { _, _ in key = ""; message = nil }
            .onDisappear { key = "" }
        }
    }

    private var openAIModelSection: some View {
        Section("OpenAI 模型与回答") {
            Picker("模型", selection: $model.openAISettings.modelID) {
                ForEach(OpenAIModelCatalog.models) { descriptor in
                    Text(descriptor.name).tag(descriptor.id)
                }
                if OpenAIModelCatalog.model(id: model.openAISettings.modelID) == nil {
                    Text("未核对：\(model.openAISettings.modelID)").tag(model.openAISettings.modelID)
                }
            }
            Picker("推理程度", selection: $model.openAISettings.reasoning) {
                ForEach(OpenAIReasoningEffort.allCases) { effort in
                    let supported = OpenAIModelCatalog.model(id: model.openAISettings.modelID)?.supportedReasoning.contains(effort) == true
                    Text(supported ? effort.label : "\(effort.label)（此型号不支持）")
                        .tag(effort).disabled(!supported)
                }
            }
            Picker("联网搜索", selection: $model.openAISettings.searchMode) {
                ForEach(OpenAISearchMode.allCases) { Text($0.label).tag($0) }
            }
            Text("自动：由模型决定是否检索。必须联网：实际要求搜索工具；未确认完成时明确提示。来源出现在回答下方，搜索不保证结论正确。")
                .font(.footnote).foregroundStyle(.secondary)
            Picker("回答长度偏好", selection: $model.openAISettings.verbosity) {
                ForEach(OpenAIVerbosity.allCases) { Text($0.label).tag($0) }
            }
            DisclosureGroup("高级参数") {
                TextField("模型 ID", text: $model.openAISettings.modelID)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                HStack {
                    Text("输出及推理总预算")
                    TextField("tokens", value: $model.openAISettings.maxOutputTokens, format: .number)
                        .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        .accessibilityLabel("输出及推理总预算，tokens")
                }
                Text("预算同时覆盖内部推理和输出，与回答长度偏好分开。当前目录依据官方能力维护；不发起账户型号查询，账户权限仍可能拒绝。未核对型号需先补能力支持才能发送。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let validation = model.openAISettings.validationMessage {
                Label(validation, systemImage: "exclamationmark.circle")
                    .font(.footnote).foregroundStyle(.orange)
            }
        }
    }

    private var instructionSection: some View {
        Section("OpenAI 应用指令") {
            Menu {
                ForEach(model.openAISettings.presets) { preset in
                    Button(preset.name) {
                        selectedPresetID = preset.id
                        presetName = preset.name
                        model.openAISettings.instructions = preset.instructions
                    }
                }
            } label: { Label("应用指令预设", systemImage: "text.book.closed") }
            TextEditor(text: $model.openAISettings.instructions)
                .frame(minHeight: 180)
                .accessibilityLabel("本次实际生效的 OpenAI 应用指令")
            HStack {
                Button {
                    UIPasteboard.general.string = model.openAISettings.instructions
                    message = "已复制当前指令。"
                } label: { Image(systemName: "doc.on.doc") }
                .help("复制当前指令").accessibilityLabel("复制当前指令")
                Spacer()
                Button {
                    model.openAISettings.instructions = OpenAISettings.defaults.instructions
                    selectedPresetID = nil
                } label: { Label("恢复默认指令", systemImage: "arrow.counterclockwise") }
            }
            TextField("预设名称", text: $presetName)
            HStack {
                Button {
                    let preset = InstructionPreset(name: cleanPresetName, instructions: model.openAISettings.instructions)
                    model.openAISettings.presets.append(preset)
                    selectedPresetID = preset.id
                } label: { Label("新建预设", systemImage: "plus") }
                .disabled(cleanPresetName.isEmpty || model.openAISettings.presets.count >= 20)
                Spacer()
                Button {
                    guard let index = model.openAISettings.presets.firstIndex(where: { $0.id == selectedPresetID }) else { return }
                    model.openAISettings.presets[index].name = cleanPresetName
                    model.openAISettings.presets[index].instructions = model.openAISettings.instructions
                } label: { Image(systemName: "square.and.arrow.down") }
                .help("保存到所选预设").accessibilityLabel("保存到所选预设")
                .disabled(selectedPresetID == nil || cleanPresetName.isEmpty)
                Button(role: .destructive) {
                    model.openAISettings.presets.removeAll { $0.id == selectedPresetID }
                    selectedPresetID = nil
                } label: { Image(systemName: "trash") }
                .help("删除所选预设").accessibilityLabel("删除所选预设")
                .disabled(selectedPresetID == nil)
            }
            Text("此处全文就是实际发送的应用指令，不追加隐藏规则。请保留适合你的图片资料、无音轨及搜索边界说明；预设只改变指令。编辑立即保存，最多 20 套预设。")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var cleanPresetName: String { String(presetName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60)) }

    private func saveKey() {
        do {
            try model.credentials.save(key, for: model.provider)
            key = ""
            model.refreshKeyStatus()
            message = "已保存，没有发起网络验证或 API 请求。"
        } catch { message = (error as? ClientError ?? .keychain).localizedDescription }
    }

    private func deleteKey() {
        do {
            try model.credentials.delete(model.provider)
            key = ""
            model.refreshKeyStatus()
            message = "已删除本机保存的密钥。"
        } catch { message = ClientError.keychain.localizedDescription }
    }
}
