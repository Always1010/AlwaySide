package com.alwayside.android

/** A null override follows future system changes instead of freezing the current appearance. */
internal enum class AppearanceMode(val value: String, val forcedNight: Boolean?) {
    SYSTEM("system", null), LIGHT("light", false), DARK("dark", true);

    fun isNight(systemNight: Boolean): Boolean = forcedNight ?: systemNight

    companion object {
        fun parse(value: String?): AppearanceMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
