package com.okamihoro.ddplaytv.playback

import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.source.handoff.PlaybackReferenceFactory
import com.xyoye.common_component.storage.file.impl.VideoStorageFile
import com.xyoye.common_component.storage.impl.VideoStorage
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.VideoEntity
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun withIndexedPlaybackFile(
    file: File,
    block: (StoredLibraryFileDescriptor) -> Unit
) {
    val database = DatabaseProvider.instance
    val library = MediaLibraryEntity.LOCAL
    val existingLibrary = runBlocking { database.getMediaLibraryDao().getById(library.id) }
    val existingVideo = runBlocking { database.getVideoDao().getVideo(file.path) }
    val storage = VideoStorage(library)
    val video = existingVideo ?: VideoEntity(fileId = 0, filePath = file.path, folderPath = file.parent!!)
    val storageFile = VideoStorageFile(storage, video)
    val existingHistory = runBlocking { database.getPlayHistoryDao().getPlayHistory(storageFile.uniqueKey(), library.id) }
    runBlocking {
        if (existingLibrary == null) database.getMediaLibraryDao().insert(library)
        if (existingVideo == null) database.getVideoDao().insert(video)
    }
    try {
        block(PlaybackReferenceFactory.fromFile(storageFile) as StoredLibraryFileDescriptor)
    } finally {
        storage.close()
        runBlocking {
            if (existingVideo == null) database.getVideoDao().deleteByPath(file.path)
            database.getPlayHistoryDao().getPlayHistory(storageFile.uniqueKey(), library.id)?.let {
                database.getPlayHistoryDao().delete(it.id)
            }
            if (existingHistory != null) database.getPlayHistoryDao().insert(existingHistory)
            if (existingLibrary == null) database.getMediaLibraryDao().delete(library.url, library.mediaType)
        }
    }
}
