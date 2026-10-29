package com.xyoye.subtitle

import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.extension.formatFileName
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.common_component.utils.getFileExtension
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.player.subtitle.backend.SubtitleRenderer
import com.xyoye.player.subtitle.backend.SubtitleRendererRegistry
import com.xyoye.player_component.utils.PlayerErrorReporter
import com.xyoye.subtitle.exception.FatalParsingException
import com.xyoye.subtitle.format.FormatFactory
import com.xyoye.subtitle.info.TimedTextObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    private var backendOwner: SubtitleRenderer? = null

    suspend fun loadSubtitle(subtitlePath: String): Boolean {
        val resolvedPath = withContext(Dispatchers.IO) { resolveToLocalPath(subtitlePath) } ?: return false
        backendOwner = null
        val renderer = SubtitleRendererRegistry.current()
        if (renderer != null) {
            val extension = getFileExtension(resolvedPath).lowercase(Locale.ROOT)
            if (renderer.supportsExternalTrack(extension)) {
                return withContext(Dispatchers.Main.immediate) {
                    val loaded = renderer.loadExternalSubtitle(resolvedPath)
                    if (loaded) {
                        backendOwner = renderer
                        mTimedTextObject = null
                    } else {
                        ToastCenter.showOriginalToast("加载 ASS/SSA 字幕失败，请检查文件内容")
                    }
                    loaded
                }
            } else if (FormatFactory.findFormat(resolvedPath) == null) {
                notifyUnsupportedFormat(extension, renderer.backend)
            }
        }
        mTimedTextObject = withContext(Dispatchers.IO) { parserSource(resolvedPath) }
        return mTimedTextObject != null
    }

    fun clearBackendSubtitle(): Boolean {
        val renderer = backendOwner ?: return false
        backendOwner = null
        renderer.clearExternalSubtitle()
        return true
    }

    fun getSubtitle(position: Long): MixedSubtitle? {
        if (backendOwner != null) {
            return null
        }
        if (mTimedTextObject == null) {
            return null
        }
        return MixedSubtitle(SubtitleType.TEXT, findSubtitle(position))
    }

    fun release() {
        clearBackendSubtitle()
        mTimedTextObject = null
    }

    private fun notifyUnsupportedFormat(
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

        // 字幕初始时间
        val minMs: Long = timedTextObject.captions.firstKey()
        // 字幕结束时间
        val maxMs: Long = timedTextObject.captions.lastKey()

        // 当前进度未达字幕初始时间
        if (position < minMs || minMs > maxMs) {
            return subtitleList
        }

        // 取当前进度前十秒
        val startMs = max(minMs, position - 10 * 1000L)
        // 取当前进度后十秒
        val endMs = min(maxMs, position + 10 * 1000L)

        // 当字幕与视频不匹配时，进度-10s任然会大于最大进度
        if (startMs > endMs) {
            return subtitleList
        }

        // 获取二十秒间所有字幕
        val subtitleCaptions = timedTextObject.captions.subMap(startMs, endMs)

        // 遍历字幕，取当前时间字幕
        for (caption in subtitleCaptions.values) {
            val captionStartMs = caption.start.getMseconds()
            val captionEndMs = caption.end.getMseconds()

            // 1ms容错
            if (position < captionStartMs - 1) {
                break
            }

            // 1ms容错
            if (position >= captionStartMs - 1L && position <= captionEndMs) {
                subtitleList.addAll(
                    SubtitleUtils.caption2Subtitle(
                        caption,
                        timedTextObject.playResX,
                        timedTextObject.playResY,
                    ),
                )
            }
        }

        return subtitleList
    }

    private fun parserSource(subtitlePath: String): TimedTextObject? {
        try {
            if (subtitlePath.isNotEmpty()) {
                // 解析字幕文件
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
            PlayerErrorReporter.report(
                e,
                "ExternalSubtitleManager",
                "parserSource",
                "Fatal error parsing subtitle file: $subtitlePath",
            )
            ToastCenter.showOriginalToast("解析外挂字幕文件失败")
        }
        return null
    }

    private fun resolveToLocalPath(rawPath: String): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.startsWith("file://")) {
            return Uri.parse(trimmed).path ?: trimmed
        }
        if (trimmed.startsWith("content://")) {
            return cacheContentUriSubtitle(trimmed)
        }
        return trimmed
    }

    private fun cacheContentUriSubtitle(uriString: String): String? {
        val context = BaseApplication.getAppContext()
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver

        val displayName =
            try {
                resolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else {
                            null
                        }
                    }
            } catch (e: Exception) {
                PlayerErrorReporter.report(
                    e,
                    "ExternalSubtitleManager",
                    "cacheContentUriSubtitle",
                    "query display name failed: $uriString",
                )
                null
            }

        val safeName =
            (displayName ?: uri.lastPathSegment ?: "subtitle")
                .formatFileName()
                .ifBlank { "subtitle" }
        val cacheKey = uriString.toMd5String()

        val dir =
            File(PathHelper.getSubtitleDirectory(), "uri_cache").apply {
                if (!exists()) {
                    mkdirs()
                }
            }
        val target = File(dir, "${cacheKey}_$safeName")
        if (target.exists() && target.length() > 0L) {
            return target.absolutePath
        }

        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                return null
            }
            target.absolutePath
        }.onFailure {
            PlayerErrorReporter.report(
                it,
                "ExternalSubtitleManager",
                "cacheContentUriSubtitle",
                "copy failed: $uriString -> ${target.absolutePath}",
            )
            runCatching { target.delete() }
        }.getOrNull()
    }
}
