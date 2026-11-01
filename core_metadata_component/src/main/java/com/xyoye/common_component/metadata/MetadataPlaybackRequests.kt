package com.xyoye.common_component.metadata

import androidx.room.withTransaction
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.SeriesPlaybackDescriptor
import com.xyoye.common_component.playback.handoff.SeriesQueueEntry
import com.xyoye.common_component.playback.handoff.SeriesQueueFile
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.PlayHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All identified library/history entries share this catalog-to-playback boundary. No network or history writes. */
class MetadataPlaybackRequests(
    private val repository: MetadataRepository = MetadataCoordinator.repository
) {
    suspend fun asset(
        assetId: Long,
        season: Int? = null,
        episode: Int? = null
    ): PlaybackDescriptor =
        withContext(Dispatchers.IO) {
            repository.database.withTransaction {
                val asset = requireNotNull(repository.dao.asset(assetId))
                build(asset, season, episode)
            }
        }

    suspend fun history(history: PlayHistoryEntity): PlaybackDescriptor =
        withContext(Dispatchers.IO) {
            repository.database.withTransaction {
                val original = PlayHistoryDescriptor(history.id, history.uniqueKey, history.storageId)
                val libraryId = history.storageId ?: return@withTransaction original
                val library = repository.database.getMediaLibraryDao().getById(libraryId)
                if (library?.mediaType != history.mediaType) return@withTransaction original
                val asset =
                    repository.dao.historyAssets(libraryId, history.uniqueKey).singleOrNull()
                        ?: return@withTransaction original
                val binding = repository.dao.binding(asset.assetId)
                if (binding?.state != "MATCHED" || binding.mediaKind != "tv" || binding.failure == "FILE_CHANGED") {
                    return@withTransaction original
                }
                build(asset, null, null)
            }
        }

    private suspend fun build(
        asset: MediaAsset,
        season: Int?,
        episode: Int?
    ): PlaybackDescriptor {
        val dao = repository.dao
        require(asset.available && repository.source(asset.libraryId).contentGeneration == asset.contentGeneration) {
            "来源或文件已失效，请重新选择"
        }
        val library = requireNotNull(repository.database.getMediaLibraryDao().getById(asset.libraryId))
        val original = StoredLibraryFileDescriptor(asset.libraryId, library.mediaType, MetadataCoordinator.reference(asset))
        val binding = dao.binding(asset.assetId)
        require(binding?.failure != "FILE_CHANGED") { "文件已变化，请重新识别" }
        if (binding?.state != "MATCHED" || binding.mediaKind != "tv" || binding.tmdbId == null) return original
        val anchorLinks = dao.bindingEpisodes(asset.assetId).filter { it.seasonNumber >= 0 && it.episodeNumber > 0 }
        val anchor =
            if (season != null && episode != null) {
                anchorLinks.find { it.seasonNumber == season && it.episodeNumber == episode }
                    ?: throw StaleMetadataResult()
            } else {
                anchorLinks.firstOrNull() ?: return original
            }
        val seriesId = requireNotNull(binding.tmdbId)
        val assets = dao.playbackAssets("tv", seriesId)
        val files =
            assets.associate { copy ->
                val source = requireNotNull(repository.database.getMediaLibraryDao().getById(copy.libraryId))
                copy.assetId to
                    SeriesQueueFile(
                        copy.assetId,
                        copy.revision,
                        copy.libraryId,
                        copy.contentGeneration,
                        source.mediaType,
                        MetadataCoordinator.reference(copy),
                        source.displayName
                    )
            }
        val links =
            dao.playbackEpisodes(seriesId).filter {
                it.assetId in files &&
                    it.seasonNumber >= 0 &&
                    it.episodeNumber > 0 &&
                    (it.seasonNumber == 0) == (anchor.seasonNumber == 0)
            }
        val episodeData = links.map { it.seasonNumber }.distinct().flatMap { dao.episodes(seriesId, it) }
        val entries =
            links
                .groupBy { it.seasonNumber to it.episodeNumber }
                .map { (key, values) ->
                    val prefix = if (key.first == 0) "特别篇" else "第 ${key.first} 季"
                    val name = episodeData.find { it.seasonNumber == key.first && it.episodeNumber == key.second }?.title.orEmpty()
                    SeriesQueueEntry(
                        key.first,
                        key.second,
                        "$prefix · 第 ${key.second} 集${if (name.isBlank()) "" else " · $name"}",
                        values.mapNotNull { files[it.assetId] }.distinctBy { it.assetId }
                    )
                }.sortedWith(compareBy<SeriesQueueEntry> { it.seasonNumber }.thenBy { it.episodeNumber })
        val index = entries.indexOfFirst { it.seasonNumber == anchor.seasonNumber && it.episodeNumber == anchor.episodeNumber }
        return SeriesPlaybackDescriptor(seriesId, entries, index, asset.libraryId, requireNotNull(files[asset.assetId]))
    }
}
