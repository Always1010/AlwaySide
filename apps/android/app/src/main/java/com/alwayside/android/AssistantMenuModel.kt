package com.alwayside.android

/** Only tools belong here. The two question checkboxes are independent of this ordered list. */
internal enum class AssistantMenuAction(val title: String) {
    SCREEN("截图问答"), CROP("框选问答"), AUDIO("听一段"), LAST("最近对话"),
    PLAY("回听上一段"), HISTORY("对话记录"), SETTINGS("设置"), STOP("关闭悬浮球"),
    CANCEL_AUDIO("取消录音"), APPEARANCE("外观设置");

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

/** Null color follows the application; null dim keeps the original system-blur fallback. */
internal data class MenuAppearance(
    val backgroundColor: Int? = null,
    val transparency: Int = 0,
    val sizePercent: Int = 100,
    val blurStrength: Int = 25,
    val dimPercent: Int? = null,
    val cornerRadiusDp: Int = 24,
) {
    init {
        require(transparency in 0..100 && sizePercent in 30..150)
        require(blurStrength in 0..100 && (dimPercent == null || dimPercent in 0..100))
        require(cornerRadiusDp in 0..100)
    }

    val scale: Float get() = sizePercent / 100f
    val panelAlpha: Int get() = ((100 - transparency) * 255 / 100f).toInt()
    val blurRadiusDp: Float get() = blurStrength * .64f
    fun effectiveDimPercent(blurSupported: Boolean): Int =
        dimPercent ?: if (blurSupported && blurStrength > 0) 18 else 32
}
