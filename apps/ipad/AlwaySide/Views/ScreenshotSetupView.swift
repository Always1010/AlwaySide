import SwiftUI

struct ScreenshotSetupView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("长按小球先截屏，保持当前窗口") {
                    Text("先在系统多任务中把助手放入 Slide Over，并收至边缘。快捷指令不负责创建小窗。")
                    Label("1. 在「快捷指令」新建或编辑现有入口", systemImage: "square.and.pencil")
                    Label("2. 第一项添加系统「截屏」动作", systemImage: "camera.viewfinder")
                    Label("3. 第二项添加 AlwaySide「接收截图（保持当前窗口）」", systemImage: "square.and.arrow.down")
                    Text("点接收动作中的「截图」参数，选择前一步「截屏」的输出变量。确认参数显示截图变量，不能使用旧照片、固定文件或剪贴板。")
                    Text("移除原来的「打开 App」或「打开 URL」动作。保留这两个动作即可；不能先打开助手再截屏，否则截到的是助手。")
                    Label("4. 将这个快捷指令绑定到 AssistiveTouch 的「长按」", systemImage: "hand.tap")
                    Text("路径：设置 → 辅助功能 → 触控 → AssistiveTouch → 自定操作 → 长按 → 选择该快捷指令。安装新版 App 不会自动改掉原来的长按绑定。")
                }
                Section("实际使用") {
                    Text("停留在要问的页面 → 长按小球 → 从屏幕边缘唤回已有助手小窗 → 核对新缩略图后提问。")
                    Text("后台接收不会打开或强制调整助手窗口。要查看助手仍需一次边缘手势；窗口是否可用由当前 iPadOS 多任务模式决定。")
                    Text("接收时不调用模型、不持续录屏。新截图替换旧画面并开始新会话；截图无效或超过 10 分钟，会明确报错，不能拿旧图继续这次提问。")
                }
                Section("可选：截屏后直接打开") {
                    Text("把第二项换成「接收截图并打开助手」。顺序仍是先「截屏」，再接收；输入也必须连接该截图。")
                    Text("此动作要求系统激活 App，可能全屏打开；无法保证恢复原 Slide Over 位置。若要保持当前窗口，请使用上面的后台接收动作。")
                }
                Section("检查入口") {
                    Text("在两个不同页面分别长按截图，每次唤回后核对缩略图确实变化。若动作不存在，请先打开一次新版 App，再回快捷指令搜索 AlwaySide。")
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
