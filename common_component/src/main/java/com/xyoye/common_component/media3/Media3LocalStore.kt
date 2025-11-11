package com.xyoye.common_component.media3

import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.data_component.entity.media3.DownloadAssetCheck
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Media3LocalStore {

    private val dao by lazy { DatabaseManager.instance.getMedia3Dao() }

    suspend fun recordSnapshot(snapshot: RolloutToggleSnapshot) = withContext(Dispatchers.IO) {
        dao.insertSnapshot(snapshot)
    }

    suspend fun recentSnapshots(limit: Int = 20): List<RolloutToggleSnapshot> =
        withContext(Dispatchers.IO) {
            dao.recentSnapshots(limit)
        }

    suspend fun upsertDownloadCheck(check: DownloadAssetCheck) = withContext(Dispatchers.IO) {
        dao.upsertDownloadCheck(check)
    }

    suspend fun latestDownloadCheck(downloadId: String): DownloadAssetCheck? =
        withContext(Dispatchers.IO) {
            dao.findDownloadCheck(downloadId)
        }
}
