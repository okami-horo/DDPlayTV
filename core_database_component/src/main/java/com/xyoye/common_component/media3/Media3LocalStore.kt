package com.xyoye.common_component.media3

import androidx.annotation.VisibleForTesting
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.database.dao.Media3Dao
import com.xyoye.data_component.entity.media3.DownloadAssetCheck
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Media3LocalStore {
    @Volatile
    private var overrideDao: Media3Dao? = null

    private val dao: Media3Dao
        get() = overrideDao ?: DatabaseManager.instance.getMedia3Dao()

    @VisibleForTesting
    fun overrideDao(testDao: Media3Dao?) {
        overrideDao = testDao
    }

    suspend fun recordSnapshot(snapshot: RolloutToggleSnapshot) =
        withContext(Dispatchers.IO) {
            dao.insertSnapshot(snapshot)
        }

    suspend fun recentSnapshots(limit: Int = 20): List<RolloutToggleSnapshot> =
        withContext(Dispatchers.IO) {
            dao.recentSnapshots(limit)
        }

    suspend fun upsertDownloadCheck(check: DownloadAssetCheck) =
        withContext(Dispatchers.IO) {
            dao.upsertDownloadCheck(check)
        }

    suspend fun latestDownloadCheck(downloadId: String): DownloadAssetCheck? =
        withContext(Dispatchers.IO) {
            dao.findDownloadCheck(downloadId)
        }
}
