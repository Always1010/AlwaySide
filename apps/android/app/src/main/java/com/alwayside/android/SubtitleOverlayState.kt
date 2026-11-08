package com.alwayside.android

/** Closing a window suppresses captions for this playback session, never the audio itself. */
internal class SubtitleOverlayState {
    private var session = -1L
    private var dismissed = false
    private val panels = mutableMapOf<Any, String>()

    fun playback(session: Long) {
        if (this.session != session) { this.session = session; dismissed = false }
    }

    fun dismiss() { dismissed = true }
    fun restore() { dismissed = false }
    fun panel(token: Any, owner: String?, visible: Boolean) {
        if (visible && owner != null) panels[token] = owner else panels.remove(token)
    }

    fun shouldShow(owner: String?, enabled: Boolean, hasSubtitles: Boolean,
                   originalAudio: Boolean, screenAvailable: Boolean): Boolean =
        enabled && owner != null && hasSubtitles && originalAudio && screenAvailable &&
            !dismissed && owner !in panels.values

    companion object {
        /** The last selected language stays selected; closing is an explicit separate action. */
        fun toggleLanguage(current: SubtitleDisplay, language: SubtitleDisplay): SubtitleDisplay = when (current) {
            SubtitleDisplay.BILINGUAL -> if (language == SubtitleDisplay.CHINESE) SubtitleDisplay.ENGLISH else SubtitleDisplay.CHINESE
            SubtitleDisplay.CHINESE -> if (language == SubtitleDisplay.ENGLISH) SubtitleDisplay.BILINGUAL else current
            SubtitleDisplay.ENGLISH -> if (language == SubtitleDisplay.CHINESE) SubtitleDisplay.BILINGUAL else current
            SubtitleDisplay.OFF -> language
        }

        fun text(bundle: SubtitleBundle, index: Int, mode: SubtitleDisplay): String {
            val cue = bundle.cues.getOrNull(index) ?: return ""
            val chinese = bundle.translations[cue.id].orEmpty().ifBlank { "中文尚未完成" }
            return when (mode) {
                SubtitleDisplay.CHINESE -> chinese
                SubtitleDisplay.ENGLISH -> cue.text
                SubtitleDisplay.BILINGUAL -> "$chinese\n${cue.text}"
                SubtitleDisplay.OFF -> ""
            }
        }
    }
}
