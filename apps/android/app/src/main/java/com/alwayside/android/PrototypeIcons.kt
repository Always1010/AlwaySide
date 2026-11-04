package com.alwayside.android

import android.content.Context
import android.graphics.drawable.Drawable

/** One line-icon family for the approved prototype; keep platform IDs at existing call sites. */
internal fun Context.prototypeIcon(resource: Int): Drawable = getDrawable(when (resource) {
    android.R.drawable.ic_media_previous, android.R.drawable.ic_menu_revert -> R.drawable.ic_line_back
    android.R.drawable.ic_media_next -> R.drawable.ic_line_next
    android.R.drawable.ic_menu_save -> R.drawable.ic_line_check
    android.R.drawable.ic_menu_send -> R.drawable.ic_line_send
    android.R.drawable.ic_menu_close_clear_cancel -> R.drawable.ic_line_close
    android.R.drawable.ic_input_add -> R.drawable.ic_line_plus
    android.R.drawable.ic_menu_more -> R.drawable.ic_line_more
    android.R.drawable.ic_menu_manage, android.R.drawable.ic_menu_preferences -> R.drawable.ic_line_settings
    android.R.drawable.ic_menu_sort_by_size -> R.drawable.ic_line_sliders
    android.R.drawable.ic_menu_edit -> R.drawable.ic_line_edit
    android.R.drawable.ic_menu_info_details -> R.drawable.ic_line_text
    android.R.drawable.ic_btn_speak_now -> R.drawable.ic_line_audio
    android.R.drawable.ic_menu_camera -> R.drawable.ic_line_scan
    android.R.drawable.ic_menu_view -> R.drawable.ic_line_sparkles
    android.R.drawable.ic_menu_directions -> R.drawable.ic_line_hand
    android.R.drawable.ic_menu_crop -> R.drawable.ic_line_crop
    android.R.drawable.ic_menu_recent_history -> R.drawable.ic_line_history
    android.R.drawable.ic_menu_delete -> R.drawable.ic_line_trash
    android.R.drawable.ic_popup_sync -> R.drawable.ic_line_retry
    R.drawable.ic_copy -> R.drawable.ic_line_copy
    R.drawable.ic_pin -> R.drawable.ic_line_pin
    R.drawable.ic_resize -> R.drawable.ic_line_resize
    else -> resource
})!!.mutate()
