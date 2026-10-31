package com.xyoye.common_component.database.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaLibraryRepository {
    fun getAll(): LiveData<MutableList<MediaLibraryEntity>> =
        DatabaseProvider.instance
            .getMediaLibraryDao()
            .getAll()

    suspend fun getById(libraryId: Int): MediaLibraryEntity? =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getMediaLibraryDao()
                .getById(libraryId)
        }

    suspend fun getByMediaType(mediaType: MediaType): MutableList<MediaLibraryEntity> =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getMediaLibraryDao()
                .getByMediaTypeSuspend(mediaType)
        }

    suspend fun getByUrl(
        url: String,
        mediaType: MediaType,
    ): MediaLibraryEntity? =
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getMediaLibraryDao()
                .getByUrl(url, mediaType)
        }

    suspend fun insert(
        vararg mediaLibraries: MediaLibraryEntity,
        invalidateRequests: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            val db = DatabaseProvider.instance
            db.withTransaction {
                val dao = db.getMediaLibraryDao()
                val metadata = db.getMetadataDao()
                for (library in mediaLibraries) {
                    val old = if (library.id > 0) dao.getById(library.id) else dao.getByUrl(library.url, library.mediaType)
                    dao.insert(library)
                    val saved = dao.getByUrl(library.url, library.mediaType) ?: continue
                    val state = metadata.source(saved.id) ?: CatalogSourceState(saved.id)
                    val contentChanged =
                        old != null &&
                            (
                                old.url != saved.url ||
                                    old.mediaType != saved.mediaType ||
                                    old.account != saved.account ||
                                    old.smbSharePath != saved.smbSharePath ||
                                    old.port != saved.port ||
                                    old.ftpAddress != saved.ftpAddress ||
                                    old.isAnonymous != saved.isAnonymous
                            )
                    val connectionChanged =
                        invalidateRequests ||
                            old == null ||
                            old.copy(displayName = saved.displayName, describe = saved.describe) != saved
                    metadata.putSource(
                        state.copy(
                            contentGeneration = state.contentGeneration + if (contentChanged) 1 else 0,
                            revision = state.revision + if (connectionChanged) 1 else 0,
                            changedAt = if (contentChanged) System.currentTimeMillis() else state.changedAt
                        )
                    )
                    if (connectionChanged) metadata.cancelSourceScans(saved.id)
                }
            }
        }
    }

    suspend fun delete(
        url: String,
        mediaType: MediaType,
    ) {
        withContext(Dispatchers.IO) {
            val db = DatabaseProvider.instance
            db.withTransaction {
                val library = db.getMediaLibraryDao().getByUrl(url, mediaType)
                if (library != null) {
                    val metadata = db.getMetadataDao()
                    metadata.cancelSourceScans(library.id)
                    metadata.deleteSourceEpisodes(library.id)
                    metadata.deleteSourceBindings(library.id)
                    metadata.deleteSourceAssets(library.id)
                    metadata.deleteSource(library.id)
                }
                db.getMediaLibraryDao().delete(url, mediaType)
            }
        }
    }

    suspend fun clearPassword(libraryId: Int) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getMediaLibraryDao()
                .clearPassword(libraryId)
        }
    }

    suspend fun invalidateCatalogRequests(libraryId: Int) {
        val db = DatabaseProvider.instance
        db.withTransaction {
            val dao = db.getMetadataDao()
            dao.source(libraryId)?.let { dao.putSource(it.copy(revision = it.revision + 1)) }
            dao.cancelSourceScans(libraryId)
        }
    }

    suspend fun clearRemoteSecret(libraryId: Int) {
        withContext(Dispatchers.IO) {
            DatabaseProvider.instance
                .getMediaLibraryDao()
                .clearRemoteSecret(libraryId)
        }
    }
}
