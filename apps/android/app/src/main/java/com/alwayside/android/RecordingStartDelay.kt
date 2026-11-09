package com.alwayside.android

/** Store tenths of a second as integers so the saved delay is exact. */
internal object RecordingStartDelay {
    const val MAX_TENTHS = 50

    fun parse(value: String): Int? {
        val text = value.trim()
        if (!Regex("(?:[0-9]+(?:\\.[0-9]?)?|\\.[0-9])").matches(text)) return null
        val seconds = text.toBigDecimalOrNull() ?: return null
        if (seconds < java.math.BigDecimal.ZERO || seconds > java.math.BigDecimal(5)) return null
        return seconds.movePointRight(1).toInt()
    }

    fun seconds(tenths: Int): String =
        if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}
