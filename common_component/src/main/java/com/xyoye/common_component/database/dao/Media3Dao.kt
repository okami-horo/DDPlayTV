package com.xyoye.common_component.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xyoye.data_component.entity.media3.DownloadAssetCheck
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot

@Dao
interface Media3Dao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: RolloutToggleSnapshot)

    @Query("SELECT * FROM media3_rollout_snapshot ORDER BY evaluatedAt DESC LIMIT :limit")
    suspend fun recentSnapshots(limit: Int): List<RolloutToggleSnapshot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloadCheck(check: DownloadAssetCheck)

    @Query("SELECT * FROM media3_download_asset_check WHERE downloadId = :downloadId LIMIT 1")
    suspend fun findDownloadCheck(downloadId: String): DownloadAssetCheck?
}
