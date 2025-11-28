package com.xyoye.subtitle

import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.getFileExtension
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.player.subtitle.backend.SubtitleRendererRegistry
import com.xyoye.subtitle.exception.FatalParsingException
import com.xyoye.subtitle.format.FormatFactory
import com.xyoye.subtitle.info.TimedTextObject
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Created by xyoye on 2020/12/14.
 *
 * 外挂字幕控制器
 */

@UnstableApi
class ExternalSubtitleManager(
    private val unsupportedFormatCallback: ((String, SubtitleRendererBackend) -> Unit)? = null
) {
    private var mTimedTextObject: TimedTextObject? = null
    private var handledByBackend = false

    fun loadSubtitle(subtitlePath: String): Boolean {
        handledByBackend = false
        val renderer = SubtitleRendererRegistry.current()
        if (renderer != null) {
            val extension = getFileExtension(subtitlePath).lowercase(Locale.ROOT)
            if (renderer.supportsExternalTrack(extension) && renderer.loadExternalSubtitle(subtitlePath)) {
                handledByBackend = true
                mTimedTextObject = null
                return true
            } else if (!renderer.supportsExternalTrack(extension)) {
                notifyUnsupportedFormat(subtitlePath, extension, renderer.backend)
            }
        }
        mTimedTextObject = parserSource(subtitlePath)
        return mTimedTextObject != null
    }

    fun getSubtitle(position: Long): MixedSubtitle? {
        if (handledByBackend) {
            return null
        }
        if (mTimedTextObject == null) {
            return null
        }
        return MixedSubtitle(SubtitleType.TEXT, findSubtitle(position))
    }

    fun release() {
        mTimedTextObject = null
        handledByBackend = false
    }

    private fun notifyUnsupportedFormat(
        subtitlePath: String,
        extension: String,
        backend: SubtitleRendererBackend
    ) {
        if (backend != SubtitleRendererBackend.LIBASS) {
            return
        }
        unsupportedFormatCallback?.invoke(extension, backend)
    }

    /**
     * 在所有字幕中找当前时间的字幕
     */
    private fun findSubtitle(position: Long): MutableList<SubtitleText> {
        val subtitleList = mutableListOf<SubtitleText>()
        val timedTextObject = mTimedTextObject ?: return subtitleList

        //字幕初始时间
        val minMs: Long = timedTextObject.captions.firstKey()
        //字幕结束时间
        val maxMs: Long = timedTextObject.captions.lastKey()

        //当前进度未达字幕初始时间
        if (position < minMs || minMs > maxMs)
            return subtitleList

        //取当前进度前十秒
        val startMs = max(minMs, position - 10 * 1000L)
        //取当前进度后十秒
        val endMs = min(maxMs, position + 10 * 1000L)

        //当字幕与视频不匹配时，进度-10s任然会大于最大进度
        if (startMs > endMs) {
            return subtitleList
        }

        //获取二十秒间所有字幕
        val subtitleCaptions = timedTextObject.captions.subMap(startMs, endMs)

        //遍历字幕，取当前时间字幕
        for (caption in subtitleCaptions.values) {
            val captionStartMs = caption.start.getMseconds()
            val captionEndMs = caption.end.getMseconds()

            //1ms容错
            if (position < captionStartMs - 1) {
                break
            }

            //1ms容错
            if (position >= captionStartMs - 1L && position <= captionEndMs) {
                subtitleList.addAll(
                    SubtitleUtils.caption2Subtitle(
                        caption,
                        timedTextObject.playResX,
                        timedTextObject.playResY
                    )
                )
            }
        }


        return subtitleList
    }


    private fun parserSource(subtitlePath: String): TimedTextObject? {
        try {
            if (subtitlePath.isNotEmpty()) {
                //解析字幕文件
                val subtitleFile = File(subtitlePath)
                if (subtitleFile.exists()) {
                    val format = FormatFactory.findFormat(subtitlePath)
                    if (format == null) {
                        ToastCenter.showOriginalToast("不支持的外挂字幕格式")
                        return null
                    }
                    val subtitleObj = format.parseFile(subtitleFile)
                    if (subtitleObj.captions.size == 0) {
                        ToastCenter.showOriginalToast("外挂字幕内容为空")
                        return null
                    }
                    return subtitleObj
                }
            }
        } catch (e: FatalParsingException) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ExternalSubtitleManager",
                "parserSource",
                "Fatal error parsing subtitle file: $subtitlePath"
            )
            e.printStackTrace()
            ToastCenter.showOriginalToast("解析外挂字幕文件失败")
        }
        return null
    }
}
