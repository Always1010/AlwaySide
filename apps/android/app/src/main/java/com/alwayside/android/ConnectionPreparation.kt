package com.alwayside.android

/** Presence is not compatibility or successful authentication. No "verified" state is inferred here. */
internal data class ConnectionPreparation(
    val addressEntered: Boolean = false,
    val credentialSaved: Boolean = false,
    val imageModelEntered: Boolean = false,
    val readable: Boolean = true,
) {
    val description: String get() = when {
        !readable -> "无法读取配置，请进入 API 连接检查或修复。"
        addressEntered && credentialSaved && imageModelEntered -> "配置已填写，尚未验证。请确认服务和模型支持图片问答。"
        else -> "连接配置尚未填完；保存配置不会自动发送测试请求。"
    }
}
