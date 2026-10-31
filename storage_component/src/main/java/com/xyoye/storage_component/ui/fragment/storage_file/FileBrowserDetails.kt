package com.xyoye.storage_component.ui.fragment.storage_file

import android.content.Context
import android.text.format.Formatter
import com.xyoye.common_component.storage.file.StorageFile

// Local indexed folders have no history key. Their path is stable within this source's browser.
internal fun StorageFile.browserKey(): String = uniqueKey().ifBlank { filePath() }

internal fun fileBrowserDetails(
    context: Context,
    file: StorageFile
): String {
    if (file.isDirectory()) return "文件夹"
    return buildList {
        if (file.fileLength() > 0) add(Formatter.formatShortFileSize(context, file.fileLength()))
        file
            .fileName()
            .substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() }
            ?.let { add(it.uppercase()) }
        val history = file.playHistory
        if (history != null) {
            if (history.videoPosition > 0 && history.videoDuration > 0) {
                add("已观看 ${(history.videoPosition * 100 / history.videoDuration).coerceIn(1, 100)}%")
            }
            if (!history.danmuPath.isNullOrEmpty()) add("弹幕")
            if (!history.subtitlePath.isNullOrEmpty()) add("字幕")
            if (!history.audioPath.isNullOrEmpty()) add("音频")
        }
    }.joinToString(" · ")
}
