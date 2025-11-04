package com.xyoye.subtitle

import android.graphics.Color
import com.xyoye.common_component.utils.DDLog
import com.xyoye.subtitle.info.Caption

/**
 * Created by xyoye on 2020/12/15.
 */

object SubtitleUtils {

    private const val TAG = "SubtitleUtils"
    private const val FRZ_BREAK_THRESHOLD = 3
    private const val SAMPLE_LIMIT = 5
    private const val SAMPLE_INTERVAL = 50
    private val ASS_NEWLINE_REGEX = Regex("""\\\\N""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")
    private var frzMergeCounter = 0

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
    fun caption2Subtitle(caption: Caption): MutableList<SubtitleText> {
        //字幕颜色
        val subtitleColor = getCaptionColor(caption.style?.color)

        //分割每行字幕
        val rawContent = caption.rawContent.orEmpty()
        val subtitle = caption.content.replace("<br />", "\n")

        if (shouldMergeVerticalLines(rawContent)) {
            val mergedLine = WHITESPACE_COLLAPSE_REGEX
                .replace(subtitle.replace("\n", " "), " ")
                .trim()

            if (mergedLine.isEmpty()) {
                return mutableListOf()
            }

            logFrzMerge(rawContent, mergedLine)
            return strings2Subtitle(subtitleColor, mergedLine)
        }

        val upperRegex = "\\N"
        val lowerRegex = "\n"
        if (subtitle.contains(upperRegex)) {
            return strings2Subtitle(subtitleColor, *(subtitle.split(upperRegex).toTypedArray()))
        }

        if (subtitle.contains(lowerRegex)) {
            return strings2Subtitle(subtitleColor, *(subtitle.split(lowerRegex).toTypedArray()))
        }

        return strings2Subtitle(subtitleColor, subtitle)
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
            DDLog.i(
                TAG,
                "[FR-001] vertical merge #$frzMergeCounter merged=\"$mergedLine\" raw=\"$preview\""
            )
        }
    }

    private fun strings2Subtitle(
        subtitleColor: Int,
        vararg subtitles: String
    ): MutableList<SubtitleText> {
        val subtitleList = mutableListOf<SubtitleText>()

        for (subtitle in subtitles) {
            //第一行以{开头，则认为是特殊字幕，现显示在顶部
            if (subtitle.startsWith("{")) {
                val endIndex = subtitle.lastIndexOf("}") + 1
                subtitleList.add(
                    if (endIndex != 0 && endIndex <= subtitle.length) {
                        //忽略{}中内容
                        SubtitleText(
                            subtitle.substring(
                                endIndex
                            ), true, subtitleColor
                        )
                    } else {
                        SubtitleText(
                            subtitle,
                            true,
                            subtitleColor
                        )
                    }
                )
            } else {
                subtitleList.add(
                    SubtitleText(
                        subtitle,
                        false,
                        subtitleColor
                    )
                )
            }
        }

        return subtitleList
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