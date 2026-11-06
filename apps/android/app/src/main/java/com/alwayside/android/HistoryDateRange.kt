package com.alwayside.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Inclusive calendar dates, interpreted in the same local zone as the list's timestamps. */
internal data class HistoryDateRange(val start: LocalDate, val end: LocalDate) {
    init { require(!end.isBefore(start)) { "结束日期不能早于开始日期" } }

    fun contains(timestamp: Long, zone: ZoneId): Boolean {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return !date.isBefore(start) && !date.isAfter(end)
    }

    companion object {
        fun restore(start: String?, end: String?): HistoryDateRange? = runCatching {
            if (start == null || end == null) null
            else HistoryDateRange(LocalDate.parse(start), LocalDate.parse(end))
        }.getOrNull()
    }
}
