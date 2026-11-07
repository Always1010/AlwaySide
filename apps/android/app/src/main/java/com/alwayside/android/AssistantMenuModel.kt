package com.alwayside.android

/** Only tools belong here. The two question checkboxes are independent of this ordered list. */
internal enum class AssistantMenuAction(val title: String) {
    SCREEN("截图问答"), CROP("框选问答"), AUDIO("听一段"), LAST("最近对话"),
    PLAY("回听上一段"), HISTORY("对话记录"), SETTINGS("设置"), STOP("关闭悬浮球"),
    CANCEL_AUDIO("取消录音");

    companion object {
        val defaults: List<AssistantMenuAction> = listOf(SCREEN, CROP, AUDIO, LAST, PLAY, HISTORY, SETTINGS)

        fun decode(saved: String?): List<AssistantMenuAction> = when {
            saved == null -> defaults
            saved.isBlank() -> emptyList()
            else -> saved.split(',').mapNotNull { id -> entries.firstOrNull { it.name == id } }.distinct()
        }

        fun encode(items: List<AssistantMenuAction>): String = items.distinct().joinToString(",") { it.name }

        fun move(items: List<AssistantMenuAction>, item: AssistantMenuAction, target: AssistantMenuAction): List<AssistantMenuAction> {
            val from = items.indexOf(item); val to = items.indexOf(target)
            if (from < 0 || to < 0 || from == to) return items
            return items.toMutableList().apply { removeAt(from); add(to, item) }
        }
    }
}
