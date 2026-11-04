package com.alwayside.android

/** Resolve a tap burst once; callers wait [gapMillis] after the most recent release. */
internal class TapSequence(val gapMillis: Long = 280) {
    private var count = 0
    private var last = Long.MIN_VALUE
    fun tap(now: Long): Int {
        count = if (count > 0 && now - last in 0..gapMillis) count + 1 else 1
        last = now
        return count.coerceAtMost(3)
    }
    fun settle(): Int = count.coerceAtMost(3).also { cancel() }
    fun cancel() { count = 0; last = Long.MIN_VALUE }
}

/** Cancellation invalidates completion even when the recorder already started stopping. */
internal class RecordingGate {
    private var epoch = 0
    private var active: Int? = null
    fun begin(): Int = (++epoch).also { active = it }
    fun cancel() { epoch++; active = null }
    fun complete(token: Int): Boolean {
        if (active != token) return false
        active = null
        return true
    }
}
