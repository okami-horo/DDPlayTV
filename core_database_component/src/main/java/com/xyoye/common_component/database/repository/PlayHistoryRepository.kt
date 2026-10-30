package com.xyoye.common_component.database.repository

import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

object PlayHistoryRepository {
    suspend fun findOrClaimLegacyHistory(
        uniqueKey: String,
        storageId: Int,
        mediaType: MediaType,
        fileUrl: String
    ): PlayHistoryEntity? =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance.getPlayHistoryDao().findOrClaimLegacyHistory(uniqueKey, storageId, mediaType, fileUrl)
        }

    suspend fun getAll(): MutableList<PlayHistoryEntity> =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .getAll()
        }

    suspend fun getSingleMediaType(mediaType: MediaType): MutableList<PlayHistoryEntity> =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .getSingleMediaType(mediaType)
        }

    suspend fun getLastPlay(vararg mediaTypes: MediaType): PlayHistoryEntity? =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .gitLastPlay(*mediaTypes)
        }

    suspend fun getPlayHistory(
        uniqueKey: String,
        storageId: Int,
    ): PlayHistoryEntity? =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .getPlayHistory(uniqueKey, storageId)
        }

    suspend fun getStorageLastPlay(storageId: Int): PlayHistoryEntity? =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .gitStorageLastPlay(storageId)
        }

    fun getPlayHistoryFlow(
        uniqueKey: String,
        storageId: Int,
    ): Flow<PlayHistoryEntity?> =
        DatabaseProvider.instance
            .getPlayHistoryDao()
            .getPlayHistoryFlow(uniqueKey, storageId)

    suspend fun insert(playHistoryEntity: PlayHistoryEntity) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .insert(playHistoryEntity)
        }
    }

    suspend fun updateDanmu(
        uniqueKey: String,
        storageId: Int,
        danmuPath: String?,
        episodeId: String?,
    ) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .updateDanmu(uniqueKey, storageId, danmuPath, episodeId)
        }
    }

    suspend fun updateSubtitle(
        uniqueKey: String,
        storageId: Int,
        subtitlePath: String?,
    ) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .updateSubtitle(uniqueKey, storageId, subtitlePath)
        }
    }

    suspend fun updateAudio(
        uniqueKey: String,
        storageId: Int,
        audioPath: String?,
    ) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .updateAudio(uniqueKey, storageId, audioPath)
        }
    }

    suspend fun delete(historyId: Int) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .delete(historyId)
        }
    }

    suspend fun deleteTypeAll(mediaTypes: List<MediaType>) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .deleteTypeAll(mediaTypes)
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getPlayHistoryDao()
                .deleteAll()
        }
    }
}
