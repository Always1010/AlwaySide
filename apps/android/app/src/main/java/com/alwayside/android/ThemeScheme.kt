package com.alwayside.android

/** A scheme owns action, selection and focus colors in both light and dark appearances. */
internal enum class ThemeScheme(val value: String) {
    BLACK("black"), BLUE("blue");
    fun accent(night: Boolean): Int = when(this) {
        BLACK -> if(night)0xFFE8EAED.toInt() else 0xFF191C20.toInt()
        BLUE -> if(night)0xFF9CC2FF.toInt() else 0xFF2155CD.toInt()
    }
    fun onAccent(night: Boolean): Int = if(night)0xFF101820.toInt() else 0xFFFFFFFF.toInt()
    companion object {
        fun parse(value: String?): ThemeScheme = entries.firstOrNull { it.value == value } ?: BLUE
    }
}
