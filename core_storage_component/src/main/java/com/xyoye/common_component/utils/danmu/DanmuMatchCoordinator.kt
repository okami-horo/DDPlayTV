package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.danmu.cache.CachedMatch
import com.xyoye.common_component.utils.danmu.cache.DanmuMatchCache
import com.xyoye.common_component.utils.danmu.cache.DanmuMatchCacheStore
import com.xyoye.common_component.utils.danmu.query.DanmuQuery
import com.xyoye.common_component.utils.danmu.source.DanmuSourceFactory
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.MatchMethod

class DanmuMatchCoordinator(
    private val danmuFinder: DanmuFinder = DanmuFinder.instance,
    private val danmuQuery: DanmuQuery = DanmuQuery.instance,
    private val danmuCache: DanmuMatchCacheStore = DanmuMatchCache,
) {
    suspend fun match(source: BaseVideoSource, mode: MatchMode): MatchOutcome {
        val danmuSource = DanmuSourceFactory.build(source) ?: return MatchOutcome.NotFound
        val hash = danmuSource.hash()
        val fileName = danmuSource.fileName()
        val fileSize = danmuSource.fileSize() ?: 0L
        if (hash == null) return fileName?.let { searchFallback(it) } ?: MatchOutcome.NotFound

        if (mode == MatchMode.AUTO && fileSize > 0) {
            danmuCache.getSuccess(hash, fileSize)?.let { return cachedToOutcome(it) }
            if (danmuCache.hasRecentFail(hash, fileSize)) return MatchOutcome.NotFound
        }

        val result = danmuQuery.match(
            hash = hash,
            fileName = fileName,
            fileSize = fileSize,
            videoDuration = ((danmuSource.durationMs() ?: 0L) / 1000).toInt(),
        )

        if (fileSize > 0 && result.isMatched && result.episodes.isNotEmpty()) {
            danmuCache.putSuccess(hash, fileSize, result.episodes.first().toCachedMatch())
        }
        if (fileSize > 0 && result.episodes.isEmpty()) {
            danmuCache.putFail(hash, fileSize)
        }

        return when {
            result.isMatched && result.episodes.isNotEmpty() ->
                matchOutcomeFromEpisode(result.episodes.first(), result.matchMethod)
            result.episodes.isNotEmpty() ->
                MatchOutcome.Candidates(result.episodes)
            fileName != null ->
                searchFallback(fileName)
            else ->
                MatchOutcome.NotFound
        }
    }

    private suspend fun searchFallback(fileName: String): MatchOutcome {
        val episodes = danmuQuery.search(fileName).flatMap { it.episodes }
        return if (episodes.isNotEmpty()) {
            MatchOutcome.Candidates(episodes.map { it.copy(isRecommend = true) })
        } else {
            MatchOutcome.NotFound
        }
    }

    private suspend fun matchOutcomeFromEpisode(episode: DanmuEpisodeData, method: MatchMethod): MatchOutcome {
        val localDanmu = danmuFinder.downloadEpisode(episode)
        return if (localDanmu != null) {
            MatchOutcome.Success(DanmuTrackResource.LocalFile(localDanmu), method, episode.shift)
        } else {
            MatchOutcome.NotFound
        }
    }

    private suspend fun cachedToOutcome(cached: CachedMatch): MatchOutcome {
        val episode = DanmuEpisodeData(
            animeId = cached.animeId.toInt(),
            animeTitle = cached.animeTitle,
            episodeId = cached.episodeId.toString(),
            episodeTitle = cached.episodeTitle,
            shift = cached.shift,
        )
        return matchOutcomeFromEpisode(episode, MatchMethod.HASH)
    }

    private fun DanmuEpisodeData.toCachedMatch(): CachedMatch = CachedMatch(
        episodeId = episodeId.toLongOrNull() ?: 0L,
        animeId = animeId.toLong(),
        animeTitle = animeTitle,
        episodeTitle = episodeTitle,
        shift = shift,
        cachedAt = System.currentTimeMillis(),
    )

    enum class MatchMode { AUTO, MANUAL }
}

sealed class MatchOutcome {
    data class Success(
        val resource: DanmuTrackResource,
        val matchMethod: MatchMethod,
        val shift: Double,
    ) : MatchOutcome()

    data class Candidates(
        val episodes: List<DanmuEpisodeData>,
    ) : MatchOutcome()

    object NotFound : MatchOutcome()
}
