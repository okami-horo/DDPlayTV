package com.xyoye.common_component.source.handoff

import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.playback.handoff.PlaybackCastVideo
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackQueueContext
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.RemoteStorageFile
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.utils.comparator.FileNameComparator
import com.xyoye.common_component.utils.comparator.FileSizeComparator
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.enums.StorageSort
import java.util.Collections

/** Capture before leaving the browser's thread. No live file or connection crosses this boundary. */
object PlaybackReferenceFactory {
    fun fromFile(
        file: StorageFile,
        sort: StorageSort = StorageSortOption.getSort(),
        hideHiddenFiles: Boolean = !AppConfig.isShowHiddenFile(),
        sortAscending: Boolean = StorageSortOption.isAsc()
    ): PlaybackDescriptor {
        if (file is TorrentStorageFile) {
            val info = file.getFileInfo()
            return TorrentDescriptor(
                info.subPath,
                info.fileIndex,
                ThunderManager.media3DownloadId(info.subPath, info.fileIndex),
                file.uniqueKey(),
                file.fileName()
            )
        }
        val selected = reference(file)
        val items =
            orderedVideos(file.storage.directoryFiles, sort, hideHiddenFiles, sortAscending)
                .map(::reference)
                .toMutableList()
        if (items.none { it.uniqueKey == selected.uniqueKey }) items.add(selected)
        val index = items.indexOfFirst { it.uniqueKey == selected.uniqueKey }
        val size = PlaybackQueueContext.MAX_FALLBACK_ITEMS
        val start = (index - size / 2).coerceIn(0, (items.size - size).coerceAtLeast(0))
        val fallback = Collections.unmodifiableList(items.drop(start).take(size))
        return StoredLibraryFileDescriptor(
            file.storage.library.id,
            file.storage.library.mediaType,
            selected,
            PlaybackQueueContext(
                file.storage.directory?.let(::reference),
                sort,
                hideHiddenFiles,
                selected.uniqueKey,
                fallback,
                sortAscending
            )
        )
    }

    fun reference(file: StorageFile): PlaybackFileReference =
        PlaybackFileReference(
            uniqueKey = file.uniqueKey(),
            storagePath = file.storagePath(),
            playHistoryId = file.playHistory?.id?.takeIf { it > 0 },
            protocolReference =
                when {
                    file is RemoteStorageFile && file.isFile() -> file.getRealFile().Id
                    else -> null
                },
            displayTitle = file.fileName()
        )

    fun fromScreencast(data: ScreencastData): ScreencastDescriptor =
        ScreencastDescriptor(
            senderAddress = requireNotNull(data.ip) { "Screencast sender is missing" },
            port = data.port,
            relatedVideos =
                Collections.unmodifiableList(
                    data.relatedVideos.map {
                        PlaybackCastVideo(
                            it.title,
                            it.uniqueKey,
                            it.episodeId,
                            it.danmuFileName,
                            it.subtitleFileName,
                            it.position,
                            it.duration
                        )
                    }
                ),
            playUniqueKey = data.playUniqueKey,
            httpHeaders = Collections.unmodifiableMap(data.httpHeader.orEmpty().toMap())
        )

    internal fun orderedVideos(
        files: List<StorageFile>,
        sort: StorageSort,
        hideHiddenFiles: Boolean,
        ascending: Boolean
    ): List<StorageFile> {
        val comparator: Comparator<StorageFile> =
            if (sort == StorageSort.NAME) {
                FileNameComparator({ it.fileName() }, { it.isDirectory() }, ascending, false)
            } else {
                FileSizeComparator({ it.fileName() }, { it.fileLength() }, { it.isDirectory() }, ascending, false)
            }
        return files
            .filter { it.isVideoFile() && (!hideHiddenFiles || !it.fileName().startsWith(".")) }
            .sortedWith(comparator)
            .distinctBy { it.uniqueKey() }
    }
}
