package com.xyoye.common_component.database.migration

import com.xyoye.common_component.config.DatabaseConfig
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Created by xyoye on 2022/1/24
 *
 * 用于手动迁移数据库数据
 */
object ManualMigration {
    suspend fun migrate() {
        migrate_6_7()
    }

    /**
     * V4.0.5, 将本地媒体库中视频的弹幕、字幕数据迁移至播放历史
     *
     * 由于数据迁移需要用到MD5，所以手动迁移数据
     */
    private suspend fun migrate_6_7() {
        val isMigrated = DatabaseConfig.isIsMigrated_6_7()
        if (isMigrated) {
            return
        }

        withContext(Dispatchers.IO) {
            val videoList = DatabaseManager.instance.getVideoDao().getAll()
            val historyList =
                videoList.mapNotNull {
                    if (it.danmuPath.isNullOrEmpty() && it.subtitlePath.isNullOrEmpty()) {
                        null
                    } else {
                        PlayHistoryEntity(
                            0,
                            "",
                            "",
                            MediaType.LOCAL_STORAGE,
                            danmuPath = it.danmuPath,
                            episodeId = it.danmuId.toString(),
                            subtitlePath = it.subtitlePath,
                            uniqueKey = md5Hex(it.filePath),
                        )
                    }
                }

            if (historyList.isNotEmpty()) {
                DatabaseManager.instance.getPlayHistoryDao().insert(*historyList.toTypedArray())
            }
            DatabaseConfig.putIsMigrated_6_7(true)
        }
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        val result = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val hexInt = byte.toInt() and 0xff
            var hexStr = Integer.toHexString(hexInt)
            if (hexStr.length < 2) {
                hexStr = "0$hexStr"
            }
            result.append(hexStr)
        }
        return result.toString()
    }
}
