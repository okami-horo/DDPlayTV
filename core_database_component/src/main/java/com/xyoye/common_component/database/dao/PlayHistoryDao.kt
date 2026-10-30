package com.xyoye.common_component.database.dao

import androidx.room.*
import com.xyoye.data_component.entity.EpisodeHistoryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.helper.MediaTypeConverter
import kotlinx.coroutines.flow.Flow

/**
 * Created by xyoye on 2021/1/19.
 */

@Dao
interface PlayHistoryDao {
    @Query("SELECT * FROM play_history WHERE id = (:id)")
    suspend fun getById(id: Int): PlayHistoryEntity?

    @Query("UPDATE play_history SET unique_key = (:uniqueKey), storage_path = (:storagePath), storage_id = (:storageId) WHERE id = (:id)")
    suspend fun backfillIdentity(
        id: Int,
        uniqueKey: String,
        storagePath: String,
        storageId: Int
    )

    @Query("SELECT * FROM play_history WHERE url != '' ORDER BY play_time DESC")
    suspend fun getAll(): MutableList<PlayHistoryEntity>

    @Query("SELECT * FROM play_history WHERE url != '' AND media_type = (:mediaType) ORDER BY play_time DESC")
    @TypeConverters(MediaTypeConverter::class)
    suspend fun getSingleMediaType(mediaType: MediaType): MutableList<PlayHistoryEntity>

    @Query("SELECT * FROM play_history WHERE media_type IN (:mediaTypes) AND url != '' ORDER BY play_time DESC LIMIT 1")
    @TypeConverters(MediaTypeConverter::class)
    suspend fun gitLastPlay(vararg mediaTypes: MediaType): PlayHistoryEntity?

    @Query("SELECT * FROM play_history WHERE storage_id = (:storageId) AND url != '' ORDER BY play_time DESC LIMIT 1")
    @TypeConverters(MediaTypeConverter::class)
    suspend fun gitStorageLastPlay(storageId: Int): PlayHistoryEntity?

    @Query("SELECT * FROM play_history WHERE unique_key = (:uniqueKey) AND media_type = (:mediaType) AND storage_id IS NULL")
    @TypeConverters(MediaTypeConverter::class)
    suspend fun getUnassignedHistory(
        uniqueKey: String,
        mediaType: MediaType
    ): List<PlayHistoryEntity>

    @Query("UPDATE play_history SET storage_id = (:storageId) WHERE id = (:id) AND storage_id IS NULL")
    suspend fun claimUnassignedHistory(
        id: Int,
        storageId: Int
    ): Int

    @Transaction
    suspend fun findOrClaimLegacyHistory(
        uniqueKey: String,
        storageId: Int,
        mediaType: MediaType,
        fileUrl: String
    ): PlayHistoryEntity? {
        getPlayHistory(uniqueKey, storageId)?.let { return it }
        if (uniqueKey.isBlank() || fileUrl.isBlank()) return null
        val legacy = getUnassignedHistory(uniqueKey, mediaType).singleOrNull() ?: return null
        if (legacy.url != fileUrl) return null
        if (claimUnassignedHistory(legacy.id, storageId) != 1) return null
        return getPlayHistory(uniqueKey, storageId)
    }

    @Query("SELECT * FROM play_history WHERE unique_key = (:uniqueKey) AND storage_id = (:storageId)")
    suspend fun getPlayHistory(
        uniqueKey: String,
        storageId: Int
    ): PlayHistoryEntity?

    @Query("SELECT * FROM play_history WHERE unique_key = (:uniqueKey) AND storage_id = (:storageId)")
    fun getPlayHistoryFlow(
        uniqueKey: String,
        storageId: Int
    ): Flow<PlayHistoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg entities: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE id = (:id)")
    suspend fun delete(id: Int)

    @Query("DELETE FROM play_history WHERE media_type IN (:mediaType)")
    @TypeConverters(MediaTypeConverter::class)
    suspend fun deleteTypeAll(mediaType: List<MediaType>)

    @Query("DELETE FROM play_history WHERE storage_id = (:storageId)")
    suspend fun deleteByStorageId(storageId: Int)

    @Query("SELECT danmu_path FROM play_history WHERE storage_id = (:storageId) AND danmu_path IS NOT NULL AND danmu_path != ''")
    suspend fun getDanmuPathsByStorageId(storageId: Int): List<String>

    @Query("DELETE FROM play_history")
    suspend fun deleteAll()

    @Query(
        "UPDATE play_history SET danmu_path = (:danmuPath), episode_id = (:episodeId) WHERE unique_key = (:uniqueKey) AND storage_id = (:storageId)",
    )
    suspend fun updateDanmu(
        uniqueKey: String,
        storageId: Int,
        danmuPath: String?,
        episodeId: String?
    )

    @Query("UPDATE play_history SET subtitle_path = (:subtitlePath) WHERE unique_key = (:uniqueKey) AND storage_id = (:storageId)")
    suspend fun updateSubtitle(
        uniqueKey: String,
        storageId: Int,
        subtitlePath: String?
    )

    @Query("UPDATE play_history SET audio_path = (:audioPath) WHERE unique_key = (:uniqueKey) AND storage_id = (:storageId)")
    suspend fun updateAudio(
        uniqueKey: String,
        storageId: Int,
        audioPath: String?
    )

    @Transaction
    @Query("SELECT * FROM play_history WHERE episode_id IN (:episodeIds)")
    fun getEpisodeHistory(episodeIds: List<String>): Flow<List<EpisodeHistoryEntity>>
}
