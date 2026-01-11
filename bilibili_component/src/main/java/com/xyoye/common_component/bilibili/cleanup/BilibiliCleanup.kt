package com.xyoye.common_component.bilibili.cleanup

import com.xyoye.common_component.bilibili.BilibiliApiPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliDanmakuBlockPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.auth.BilibiliAuthStore
import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSessionStore
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.data_component.entity.MediaLibraryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BilibiliCleanup {
    suspend fun cleanup(library: MediaLibraryEntity) {
        val storageId = library.id
        if (storageId <= 0) return

        val storageKey = BilibiliPlaybackPreferencesStore.storageKey(library)
        withContext(Dispatchers.IO) {
            runCatching {
                val playHistoryDao = DatabaseManager.instance.getPlayHistoryDao()

                val danmuPaths = playHistoryDao.getDanmuPathsByStorageId(storageId)
                deleteBilibiliDanmuFiles(danmuPaths)

                playHistoryDao.deleteByStorageId(storageId)

                BilibiliPlaybackPreferencesStore.clear(library)
                BilibiliDanmakuBlockPreferencesStore.clear(library)
                BilibiliApiPreferencesStore.clear(library)
                BilibiliAuthStore.clear(storageKey)
                BilibiliCookieJarStore(storageKey).clear()
                BilibiliPlaybackSessionStore.clearStorage(storageId)
                deleteBilibiliMpdFiles()
            }.onFailure { e ->
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BilibiliCleanup",
                    "cleanup",
                    "storageId=$storageId",
                )
            }
        }
    }

    private fun deleteBilibiliDanmuFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val danmuDir = PathHelper.getDanmuDirectory().absolutePath
        paths.forEach { path ->
            runCatching {
                val file = File(path)
                if (!file.exists() || !file.isFile) return@runCatching
                if (!file.absolutePath.startsWith(danmuDir)) return@runCatching
                if (!file.name.startsWith("bilibili_") || !file.name.endsWith(".xml")) return@runCatching
                file.delete()
            }
        }
    }

    private fun deleteBilibiliMpdFiles() {
        runCatching {
            val dir = PathHelper.getPlayCacheDirectory()
            val files =
                dir
                    .listFiles()
                    ?.filter { it.isFile && it.name.startsWith("bilibili_") && it.name.endsWith(".mpd") }
                    .orEmpty()
            files.forEach { it.delete() }
        }
    }
}
