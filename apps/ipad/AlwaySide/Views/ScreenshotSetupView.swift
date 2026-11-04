import SwiftUI

struct ScreenshotSetupView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("先体验：原地看一眼") {
                    Text("在「快捷指令」新建一个只含 AlwaySide「体验原地卡片（不联网）」的快捷指令，并绑定 AssistiveTouch 长按。")
                    Text("在阅读页面运行，确认出现示例卡片，划走后仍能接着读。这一步不截图、不读取密钥、不联网。系统卡片在此长按入口的实际表现需在本机确认。")
                }
                Section("长按读懂截图") {
                    Text("先在 App 的「模型与密钥」中保存 API Key。此入口每次运行都会把本次截图上传至所选供应商，使用现有模型、推理、搜索与应用指令设置，并产生 API 用量。用途固定为翻译并简要解释。")
                    Label("1. 在「快捷指令」新建或编辑现有入口", systemImage: "square.and.pencil")
                    Label("2. 第一项添加系统「截屏」动作", systemImage: "camera.viewfinder")
                    Label("3. 第二项添加 AlwaySide「读懂截图（上传并原地显示）」", systemImage: "text.viewfinder")
                    Text("将动作中的「截图」参数连接到前一步「截屏」的输出变量。不能使用旧照片、固定文件或剪贴板。")
                    Text("移除原来的「打开 App」或「打开 URL」动作。保留这两个动作即可；不能先打开助手再截屏，否则截到的是助手。")
                    Label("4. 将这个快捷指令绑定到 AssistiveTouch 的「长按」", systemImage: "hand.tap")
                    Text("路径：设置 → 辅助功能 → 触控 → AssistiveTouch → 自定操作 → 长按 → 选择该快捷指令。安装新版 App 不会自动改掉原来的长按绑定。")
                }
                Section("实际使用") {
                    Text("停留在要看的页面 → 长按小球 → 等待系统执行进度 → 阅读结果卡片 → 划走后继续。无需选图、输入问题或提前设置 Slide Over。")
                    Text("卡片的展开不会再次请求模型；只有主动点「在 App 中追问」才会打开助手，并带入同一张截图及已生成的回答。系统可能全屏打开。")
                    Text("模型请求最多等待 25 秒；失败后请回到目标页面重新长按，不会自动重试或打开 App。只暂存最新一次结果，10 分钟后不可继续打开卡片记录；进入 App 追问后才保存到本机历史。")
                    Text("等待时可使用系统提供的取消入口。已上传数据无法撤回，取消或超时仍可能产生用量。系统决定进度与卡片的具体呈现，不承诺原 App 的视频播放或触控不受影响。")
                }
                Section("备用：只收图，手动提问") {
                    Text("如果暂时不希望运行即上传，可把第二项换成「接收截图（保持当前窗口）」。此动作只存本机、不发送；之后手动从边缘唤回已设置的 Slide Over，再点击发送。")
                    Text("「接收截图并打开助手」也保留，但会请求打开 App，可能全屏。它不是原地阅读入口。")
                }
                Section("检查入口") {
                    Text("在两个内容不同的页面分别长按，核对回答对应各自页面；必要时点「在 App 中追问」检查截图。若动作不存在，请先打开一次新版 App，再回快捷指令搜索 AlwaySide。")
                    Text("不要从控制中心控件验证卡片，Apple 不支持该入口显示 snippet。请使用上面的快捷指令/AssistiveTouch 入口。")
                    Text("系统截屏失败、受保护内容、快捷指令权限或变量连错，都需要在快捷指令中处理。助手不能绕过系统限制或证明截图来自哪个 App。")
                }
            }
            .navigationTitle("截图入口设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("关闭")
                        .help("关闭")
                }
            }
        }
    }
}
