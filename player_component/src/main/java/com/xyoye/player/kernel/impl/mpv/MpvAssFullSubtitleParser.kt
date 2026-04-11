package com.xyoye.player.kernel.impl.mpv

import java.util.LinkedHashMap
import kotlin.math.max

internal class MpvAssFullSubtitleParser {
    internal data class Sample(
        val timecodeMs: Long,
        val durationMs: Long,
        val data: ByteArray
    )

    private data class DedupEntry(
        var readOrder: Int,
        var endTimeMs: Long,
        var lastSeenSnapshot: Long
    )

    private data class ParsedEvent(
        val endTimeMs: Long,
        val sample: Sample?
    )

    private val readOrderMap = LinkedHashMap<String, DedupEntry>(MAX_DEDUP_ENTRIES, 0.75f, true)
    private var nextReadOrder = 0
    private var snapshotCounter = 0L

    fun reset() {
        readOrderMap.clear()
        nextReadOrder = 0
        snapshotCounter = 0L
    }

    fun parse(assFull: String?): List<Sample> {
        if (assFull.isNullOrBlank()) return emptyList()
        val samples = ArrayList<Sample>()
        snapshotCounter++
        var latestEndTimeMs = Long.MIN_VALUE
        for (line in assFull.lineSequence()) {
            val parsed = parseLine(line, snapshotCounter) ?: continue
            latestEndTimeMs = max(latestEndTimeMs, parsed.endTimeMs)
            parsed.sample?.let(samples::add)
        }
        if (latestEndTimeMs != Long.MIN_VALUE) {
            pruneHistory(latestEndTimeMs)
        }
        return samples
    }

    internal fun historySize(): Int = readOrderMap.size

    private fun parseLine(
        line: String,
        snapshotId: Long
    ): ParsedEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(DIALOGUE_PREFIX)) return null
        val payload = trimmed.substring(DIALOGUE_PREFIX.length).trimStart()
        val fields = splitAssFields(payload) ?: return null

        val layer = fields[0].trim()
        val startMs = parseAssTimeMs(fields[1]) ?: return null
        val endMs = parseAssTimeMs(fields[2]) ?: return null
        val style = fields[3].trim()
        val name = fields[4].trim()
        val marginL = fields[5].trim()
        val marginR = fields[6].trim()
        val marginV = fields[7].trim()
        val effect = fields[8].trim()
        val text = fields[9].trimStart()

        val durationMs = endMs - startMs
        if (durationMs < 0) return null

        val eventKey =
            buildString {
                append(layer)
                append('|')
                append(startMs)
                append('|')
                append(endMs)
                append('|')
                append(style)
                append('|')
                append(name)
                append('|')
                append(marginL)
                append('|')
                append(marginR)
                append('|')
                append(marginV)
                append('|')
                append(effect)
                append('|')
                append(text)
            }

        val existing = readOrderMap[eventKey]
        if (existing != null) {
            existing.endTimeMs = max(existing.endTimeMs, endMs)
            existing.lastSeenSnapshot = snapshotId
            return ParsedEvent(endMs, null)
        }

        val readOrder = nextReadOrder++
        readOrderMap[eventKey] =
            DedupEntry(
                readOrder = readOrder,
                endTimeMs = endMs,
                lastSeenSnapshot = snapshotId,
            )
        val eventFields =
            buildString {
                append(readOrder)
                append(',')
                append(layer)
                append(',')
                append(style)
                append(',')
                append(name)
                append(',')
                append(marginL)
                append(',')
                append(marginR)
                append(',')
                append(marginV)
                append(',')
                append(effect)
                append(',')
                append(text)
            }

        return ParsedEvent(
            endTimeMs = endMs,
            sample = Sample(startMs, durationMs, eventFields.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun pruneHistory(latestEndTimeMs: Long) {
        val staleCutoffMs = latestEndTimeMs - STALE_HISTORY_WINDOW_MS
        val iterator = readOrderMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val staleByTime = entry.endTimeMs < staleCutoffMs
            val staleBySnapshots = snapshotCounter - entry.lastSeenSnapshot > STALE_SNAPSHOT_WINDOW
            if (staleByTime && staleBySnapshots) {
                iterator.remove()
            }
        }
        while (readOrderMap.size > MAX_DEDUP_ENTRIES) {
            val oldestKey = readOrderMap.entries.iterator().next().key
            readOrderMap.remove(oldestKey)
        }
        reindexReadOrdersIfNeeded()
    }

    private fun reindexReadOrdersIfNeeded() {
        if (readOrderMap.isEmpty()) {
            nextReadOrder = 0
            return
        }
        if (nextReadOrder < READ_ORDER_REINDEX_THRESHOLD) {
            return
        }
        var next = 0
        readOrderMap.values.forEach { entry ->
            entry.readOrder = next++
        }
        nextReadOrder = next
    }

    private fun splitAssFields(payload: String): List<String>? {
        val fields = ArrayList<String>(ASS_DIALOGUE_FIELDS)
        var start = 0
        var commas = 0
        for (index in payload.indices) {
            if (payload[index] == ',' && commas < ASS_DIALOGUE_DELIMITERS) {
                fields.add(payload.substring(start, index))
                start = index + 1
                commas++
            }
        }
        fields.add(payload.substring(start))
        return if (fields.size == ASS_DIALOGUE_FIELDS) fields else null
    }

    private fun parseAssTimeMs(raw: String): Long? {
        val value = raw.trim()
        val firstColon = value.indexOf(':')
        if (firstColon <= 0) return null
        val secondColon = value.indexOf(':', firstColon + 1)
        if (secondColon < 0) return null
        val dot = value.indexOf('.', secondColon + 1)
        if (dot < 0) return null

        val hours = value.substring(0, firstColon).toIntOrNull() ?: return null
        val minutes = value.substring(firstColon + 1, secondColon).toIntOrNull() ?: return null
        val seconds = value.substring(secondColon + 1, dot).toIntOrNull() ?: return null
        val centiseconds = value.substring(dot + 1).toIntOrNull() ?: return null
        if (minutes !in 0..59 || seconds !in 0..59 || centiseconds !in 0..99) return null

        val totalSeconds = hours * 3600L + minutes * 60L + seconds
        return totalSeconds * 1000L + centiseconds * 10L
    }

    companion object {
        private const val DIALOGUE_PREFIX = "Dialogue:"
        private const val ASS_DIALOGUE_FIELDS = 10
        private const val ASS_DIALOGUE_DELIMITERS = 9
        private const val MAX_DEDUP_ENTRIES = 512
        private const val STALE_HISTORY_WINDOW_MS = 60_000L
        private const val STALE_SNAPSHOT_WINDOW = 2L
        private const val READ_ORDER_REINDEX_THRESHOLD = 100_000
    }
}
