package com.xyoye.subtitle.ass

import kotlin.math.absoluteValue

/**
 * ASS 覆写标签解析工具：负责提取首个覆写块并解析常用标签。
 */
object AssOverrideParser {

    data class Alignment(val value: Int)

    data class Position(val x: Float, val y: Float)

    data class Move(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val startTime: Float? = null,
        val endTime: Float? = null
    )

    data class Rotation(val angle: Float)

    data class PlayRes(val width: Int, val height: Int)

    const val DEFAULT_PLAY_RES_X = 1280
    const val DEFAULT_PLAY_RES_Y = 720
    val DEFAULT_PLAY_RES = PlayRes(DEFAULT_PLAY_RES_X, DEFAULT_PLAY_RES_Y)

    private const val MAX_COORDINATE_ABS = 100_000f
    private const val MAX_TIME_ABS = 3_600_000f

    private val anPattern = Regex("""\\\\an(\\d)""", RegexOption.IGNORE_CASE)
    private val posPattern = Regex(
        """\\\\pos\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)""",
        RegexOption.IGNORE_CASE
    )
    private val movePattern = Regex(
        """\\\\move\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*)?\\)""",
        RegexOption.IGNORE_CASE
    )
    private val frzPattern = Regex("""\\\\frz\\s*(-?\\d+(?:\\.\\d+)?)""", RegexOption.IGNORE_CASE)

    /**
     * 解析原始字幕文本，提取首个覆写块内的可识别标签。
     */
    fun parseFirstBlock(raw: String): Map<String, String> {
        val startIndex = raw.indexOf('{')
        if (startIndex == -1) {
            return emptyMap()
        }

        var depth = 0
        for (index in startIndex until raw.length) {
            when (raw[index]) {
                '{' -> depth++
                '}' -> {
                    if (depth == 0) {
                        // 忽略孤立的 '}'
                        continue
                    }
                    depth--
                    if (depth == 0) {
                        val block = raw.substring(startIndex + 1, index)
                        return extractRecognizedTags(block)
                    }
                }
            }
        }
        return emptyMap()
    }

    private fun extractRecognizedTags(block: String): Map<String, String> {
        if (block.isBlank()) {
            return emptyMap()
        }

        val result = mutableMapOf<String, String>()

        anPattern.findAll(block).forEach { match ->
            result["an"] = match.groupValues[1]
        }

        posPattern.findAll(block).forEach { match ->
            result["pos"] = listOf(match.groupValues[1], match.groupValues[2]).joinToString(",")
        }

        movePattern.findAll(block).forEach { match ->
            val values = mutableListOf<String>()
            for (groupIndex in 1..6) {
                val rawValue = match.groups[groupIndex]?.value
                if (!rawValue.isNullOrBlank()) {
                    values.add(rawValue)
                }
            }
            if (values.isNotEmpty()) {
                result["move"] = values.joinToString(",")
            }
        }

        frzPattern.findAll(block).forEach { match ->
            result["frz"] = match.groupValues[1]
        }

        return result
    }

    /**
     * 解析 \an 标签，返回九宫格对齐值（1..9）。
     */
    fun parseAn(tagMap: Map<String, String>): Alignment? {
        val raw = tagMap["an"]?.trim() ?: return null
        val alignValue = raw.toIntOrNull() ?: return null
        return if (alignValue in 1..9) Alignment(alignValue) else null
    }

    /**
     * 解析 \pos(x,y) 标签
     */
    fun parsePos(tagMap: Map<String, String>): Position? {
        val raw = tagMap["pos"] ?: return null
        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.size < 2) {
            return null
        }
        val x = tokens[0].toFloatOrNull() ?: return null
        val y = tokens[1].toFloatOrNull() ?: return null
        if (!isFiniteCoordinate(x) || !isFiniteCoordinate(y)) {
            return null
        }
        return Position(x, y)
    }

    /**
     * 解析 \move(x1,y1,x2,y2[,t1,t2]) 标签
     */
    fun parseMove(tagMap: Map<String, String>): Move? {
        val raw = tagMap["move"] ?: return null
        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.size < 4) {
            return null
        }

        val x1 = tokens[0].toFloatOrNull() ?: return null
        val y1 = tokens[1].toFloatOrNull() ?: return null
        val x2 = tokens[2].toFloatOrNull() ?: return null
        val y2 = tokens[3].toFloatOrNull() ?: return null

        if (
            !isFiniteCoordinate(x1) ||
            !isFiniteCoordinate(y1) ||
            !isFiniteCoordinate(x2) ||
            !isFiniteCoordinate(y2)
        ) {
            return null
        }

        val t1 = tokens.getOrNull(4)?.toFloatOrNull()
        val t2 = tokens.getOrNull(5)?.toFloatOrNull()

        if (!isFiniteTime(t1) || !isFiniteTime(t2)) {
            return null
        }

        if (t1 != null && t2 != null && t1 > t2) {
            return null
        }

        return Move(x1, y1, x2, y2, t1, t2)
    }

    /**
     * 解析 \frz(angle) 标签
     */
    fun parseFrz(tagMap: Map<String, String>): Rotation? {
        val raw = tagMap["frz"]?.trim() ?: return null
        val angle = raw.toFloatOrNull() ?: return null
        if (!angle.isFinite()) {
            return null
        }
        return Rotation(normalizeAngle(angle))
    }

    private fun normalizeAngle(angle: Float): Float {
        val remainder = angle % 360f
        return if (remainder == 0f) 0f else remainder
    }

    private fun isFiniteCoordinate(value: Float): Boolean {
        return value.isFinite() && value.absoluteValue <= MAX_COORDINATE_ABS
    }

    private fun isFiniteTime(value: Float?): Boolean {
        if (value == null) {
            return true
        }
        return value.isFinite() && value.absoluteValue <= MAX_TIME_ABS
    }
}
