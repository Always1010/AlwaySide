package com.alwayside.android

import java.util.UUID

/** Only the permission bridge for the current request may release its continuation. */
internal class CapturePermissionGate {
    internal data class Pending(val id: String, val continuation: (() -> Unit)?)
    private var active: Pending? = null
    val isPending: Boolean get() = active != null

    fun begin(continuation: (() -> Unit)? = null): String {
        check(active == null) { "采集授权仍在处理中" }
        return Pending(UUID.randomUUID().toString(), continuation).also { active = it }.id
    }

    fun consume(id: String?): Pending? {
        val request = active ?: return null
        if (id != request.id) return null
        active = null
        return request
    }

    fun cancel() { active = null }

    companion object { const val EXTRA_REQUEST_ID = "captureRequestId" }
}
