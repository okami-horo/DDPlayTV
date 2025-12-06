package com.xyoye.subtitle

import android.graphics.Color
import android.os.SystemClock
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.subtitle.ass.AssOverrideParser
import com.xyoye.subtitle.info.Caption
import java.util.Locale

/**
 * Created by xyoye on 2020/12/15.
 */

object SubtitleUtils {

    private const val TAG = "SubtitleUtils"
    private const val FRZ_BREAK_THRESHOLD = 3
    private const val SAMPLE_LIMIT = 5
    private const val SAMPLE_INTERVAL = 50
    private val ASS_NEWLINE_REGEX = Regex("""\\N""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")
    private var frzMergeCounter = 0
    private var captionPerfCounter = 0
    private const val PERFORMANCE_SAMPLE_LIMIT = 5
    private const val PERFORMANCE_SAMPLE_INTERVAL = 50

    /**
     * 文字转换显示需要字幕格式
     */
    fun caption2Subtitle(captionText: String?): MutableList<SubtitleText> {
        if (captionText.isNullOrEmpty())
            return mutableListOf()

        val newCaption = Caption()
        newCaption.content = captionText
        return caption2Subtitle(newCaption)
    }

    /**
     * caption转换显示需要字幕格式
     */
    fun caption2Subtitle(
        caption: Caption,
        playResX: Int? = null,
        playResY: Int? = null
    ): MutableList<SubtitleText> {
        //字幕颜色
        val subtitleColor = getCaptionColor(caption.style?.color)

        //分割每行字幕
        val rawContent = caption.rawContent.orEmpty()
        val subtitle = caption.content.replace("<br />", "\n")

        val startNs = SystemClock.elapsedRealtimeNanos()

        val tagMap = AssOverrideParser.parseAllBlocks(rawContent)
        val alignment = AssOverrideParser.parseAn(tagMap)?.value
        val move = AssOverrideParser.parseMove(tagMap)
        val position = AssOverrideParser.parsePos(tagMap)
        val rotation = AssOverrideParser.parseFrz(tagMap)?.angle

        val scriptX = move?.toX ?: position?.x
        val scriptY = move?.toY ?: position?.y
        val effectivePlayResX = playResX?.takeIf { it > 0 }
        val effectivePlayResY = playResY?.takeIf { it > 0 }

        val result = when {
            shouldMergeVerticalLines(rawContent) -> {
                val mergedLine = WHITESPACE_COLLAPSE_REGEX
                    .replace(subtitle.replace("\n", " "), " ")
                    .trim()

                if (mergedLine.isEmpty()) {
                    mutableListOf()
                } else {
                    logFrzMerge(rawContent, mergedLine)
                    buildSubtitleList(
                        subtitleColor,
                        listOf(mergedLine),
                        alignment,
                        scriptX,
                        scriptY,
                        rotation,
                        effectivePlayResX,
                        effectivePlayResY
                    )
                }
            }

            subtitle.contains("\\N") -> {
                buildSubtitleList(
                    subtitleColor,
                    subtitle.split("\\N"),
                    alignment,
                    scriptX,
                    scriptY,
                    rotation,
                    effectivePlayResX,
                    effectivePlayResY
                )
            }

            subtitle.contains('\n') -> {
                buildSubtitleList(
                    subtitleColor,
                    subtitle.split('\n'),
                    alignment,
                    scriptX,
                    scriptY,
                    rotation,
                    effectivePlayResX,
                    effectivePlayResY
                )
            }

            else -> {
                buildSubtitleList(
                    subtitleColor,
                    listOf(subtitle),
                    alignment,
                    scriptX,
                    scriptY,
                    rotation,
                    effectivePlayResX,
                    effectivePlayResY
                )
            }
        }

        return logCaptionPerformance(startNs, result, rawContent, tagMap)
    }

    private fun shouldMergeVerticalLines(rawContent: String): Boolean {
        if (!rawContent.contains("\\frz", ignoreCase = true)) {
            return false
        }
        val breakCount = ASS_NEWLINE_REGEX.findAll(rawContent).count()
        return breakCount >= FRZ_BREAK_THRESHOLD
    }

    private fun logFrzMerge(rawContent: String, mergedLine: String) {
        frzMergeCounter++
        if (frzMergeCounter <= SAMPLE_LIMIT || frzMergeCounter % SAMPLE_INTERVAL == 0) {
            val preview = rawContent
                .replace("\n", " ")
                .replace("\r", " ")
                .take(120)
            LogFacade.d(
                LogModule.PLAYER,
                TAG,
                "[FR-001] vertical merge #$frzMergeCounter merged=\"$mergedLine\" raw=\"$preview\""
            )
        }
    }

    private fun buildSubtitleList(
        subtitleColor: Int,
        subtitles: List<String>,
        align: Int?,
        x: Float?,
        y: Float?,
        rotation: Float?,
        playResX: Int?,
        playResY: Int?
    ): MutableList<SubtitleText> {
        val subtitleList = mutableListOf<SubtitleText>()

        val totalLines = subtitles.size.coerceAtLeast(1)

        subtitles.forEachIndexed { index, subtitle ->
            if (subtitle.isEmpty()) {
                return@forEachIndexed
            }

            if (subtitle.startsWith("{")) {
                val endIndex = subtitle.lastIndexOf("}") + 1
                val content = if (endIndex != 0 && endIndex <= subtitle.length) {
                    subtitle.substring(endIndex)
                } else {
                    subtitle
                }
                subtitleList.add(
                    SubtitleText(
                        text = content,
                        top = true,
                        color = subtitleColor,
                        x = x,
                        y = y,
                        align = align,
                        rotation = rotation,
                        playResX = playResX,
                        playResY = playResY,
                        lineIndex = index,
                        lineCount = totalLines
                    )
                )
            } else {
                subtitleList.add(
                    SubtitleText(
                        text = subtitle,
                        top = false,
                        color = subtitleColor,
                        x = x,
                        y = y,
                        align = align,
                        rotation = rotation,
                        playResX = playResX,
                        playResY = playResY,
                        lineIndex = index,
                        lineCount = totalLines
                    )
                )
            }
        }

        return subtitleList
    }

    private fun logCaptionPerformance(
        startNs: Long,
        subtitleList: MutableList<SubtitleText>,
        rawContent: String,
        tagMap: Map<String, String>
    ): MutableList<SubtitleText> {
        val durationNs = SystemClock.elapsedRealtimeNanos() - startNs
        captionPerfCounter++
        if (shouldSample(captionPerfCounter, PERFORMANCE_SAMPLE_LIMIT, PERFORMANCE_SAMPLE_INTERVAL)) {
            val durationMs = durationNs / 1_000_000.0
            val tagSummary = if (tagMap.isEmpty()) "-" else tagMap.keys.joinToString("/")
            LogFacade.d(
                LogModule.PLAYER,
                TAG,
                String.format(
                    Locale.US,
                    "[FR-010] caption2Subtitle %.3fms lines=%d tags=%s rawLen=%d",
                    durationMs,
                    subtitleList.size,
                    tagSummary,
                    rawContent.length
                )
            )
        }
        return subtitleList
    }

    private fun shouldSample(counter: Int, limit: Int, interval: Int): Boolean {
        return counter <= limit || counter % interval == 0
    }


    /**
     * 获取字幕颜色
     */
    private fun getCaptionColor(colorStr: String?): Int {
        var rgbaText = colorStr

        if (rgbaText.isNullOrEmpty())
            return Color.WHITE

        if (!rgbaText.startsWith("#"))
            rgbaText = "#$colorStr"

        //颜色字符串为rgba格式，要转换成argb
        return try {
            val rgba = Color.parseColor(rgbaText)
            rgba ushr 8 or (rgba shl 32 - 8)
        } catch (e: IllegalArgumentException) {
            Color.WHITE
        }
    }
}
