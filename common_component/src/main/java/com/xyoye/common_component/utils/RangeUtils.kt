package com.xyoye.common_component.utils

import okhttp3.internal.toLongOrDefault

object RangeUtils {
    fun getRange(
        rangeText: String,
        contentLength: Long
    ): Array<Long> {
        val rangArray = Array<Long>(3) { 0 }

        val range =
            parseRange(rangeText, contentLength)
                ?: return rangArray

        rangArray[0] = range.first
        rangArray[1] = range.second
        rangArray[2] = contentLength
        return rangArray
    }

    /**
     * 解析Range请求头的值
     */
    fun parseRange(
        rangeValue: String,
        contentLength: Long
    ): Pair<Long, Long>? {
        if (contentLength <= 0) {
            return null
        }

        val maxRange = contentLength - 1
        val header = "bytes="
        val normalized = rangeValue.trim()
        if (!normalized.startsWith(header, ignoreCase = true)) {
            return null
        }
        val range = normalized.substring(header.length).trim()
        // Multiple ranges are not supported.
        if (range.contains(",")) {
            return null
        }

        // e.g. "" or "-"
        if (range.length < 2) {
            return null
        }
        val separatorIndex = range.indexOf('-')
        if (separatorIndex == -1) return null

        // e.g. "-500" (suffix bytes)
        if (separatorIndex == 0) {
            val suffixLength = range.substring(1).toLongOrDefault(0L)
            if (suffixLength <= 0) return null
            val start = (contentLength - suffixLength).coerceAtLeast(0L)
            return start to maxRange
        }

        val start = range.substring(0, separatorIndex).toLongOrDefault(-1L)
        if (start < 0 || start > maxRange) return null

        // e.g. "500-"
        if (separatorIndex == range.length - 1) {
            return start to maxRange
        }

        // e.g. "500-999"
        val end = range.substring(separatorIndex + 1).toLongOrDefault(-1L)
        return if (end < 0 || end > maxRange || start > end) {
            null
        } else {
            start to end
        }
    }
}
