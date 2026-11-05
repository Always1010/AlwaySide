package com.alwayside.android

/** Presence checks only; active answer completeness and saved service resources are separate. */
internal data class ConnectionPreparation(
    val addressEntered: Boolean = false,
    val credentialSaved: Boolean = false,
    val imageModelEntered: Boolean = false,
    val readable: Boolean = true,
    val serviceResourcesReady: Boolean = addressEntered && credentialSaved,
    val transcriptionConfigured: Boolean = false,
) {
    val description: String get() = when {
        !readable -> "无法读取配置，请进入管理 API 服务检查或修复。"
        addressEntered && credentialSaved && imageModelEntered -> "当前图文问答配置已填写，尚未验证。请确认服务和模型支持图片问答。"
        else -> "请在 AI 服务中添加 API 密钥和图文问答卡片，保存后回列表点卡片选用；保存不会自动测试。"
    }
}
