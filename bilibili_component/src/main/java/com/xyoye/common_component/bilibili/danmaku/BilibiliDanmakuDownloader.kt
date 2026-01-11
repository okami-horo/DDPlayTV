package com.xyoye.common_component.bilibili.danmaku

import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.data_component.bean.LocalDanmuBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BilibiliDanmakuDownloader {
    suspend fun getOrDownload(
        storageKey: String,
        cid: Long,
        forceRefresh: Boolean = false
    ): LocalDanmuBean? {
        if (cid <= 0) return null
        val danmuFile = File(PathHelper.getDanmuDirectory(), "bilibili_$cid.xml")
        if (!forceRefresh && danmuFile.exists() && danmuFile.length() > 0) {
            return LocalDanmuBean(danmuFile.absolutePath)
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val repository = BilibiliRepository(storageKey)
                val body =
                    repository.danmakuXml(cid).getOrNull()
                        ?: repository.danmakuListSo(cid).getOrNull()
                        ?: return@runCatching null

                val tmp = File(danmuFile.parentFile, "${danmuFile.name}.tmp")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (tmp.length() <= 0) {
                    tmp.delete()
                    return@runCatching null
                }
                if (danmuFile.exists()) {
                    danmuFile.delete()
                }
                tmp.renameTo(danmuFile)
                LocalDanmuBean(danmuFile.absolutePath)
            }.onFailure { e ->
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "BilibiliDanmakuDownloader",
                    "getOrDownload",
                    "cid=$cid",
                )
            }.getOrNull()
        }
    }
}
