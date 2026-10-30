package com.xyoye.common_component.utils.danmu.query

import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuContentData
import com.xyoye.data_component.data.DanmuRelatedUrlData
import com.xyoye.data_component.data.DanmuSearchData
import com.xyoye.data_component.data.MatchMethod
import com.xyoye.data_component.data.MatchResult
import kotlinx.coroutines.CancellationException

/**
 * Created by xyoye on 2024/1/14.
 */

class DanmuQueryImpl(
    private val searchRequest: suspend (String) -> Result<DanmuSearchData> = ResourceRepository::searchDanmu
) : DanmuQuery {
    override suspend fun match(
        hash: String,
        fileName: String?,
        fileSize: Long,
        videoDuration: Int
    ): MatchResult {
        val normalizedFileName = fileName?.takeIf { it.isNotBlank() }
        val requestFileName = normalizedFileName ?: "empty"
        val matchMode = if (normalizedFileName == null) "hashOnly" else "hashAndFileName"
        val result =
            ResourceRepository
                .matchDanmu(hash, requestFileName, fileSize, videoDuration, matchMode)
                .getOrNull()
                ?: return MatchResult(false, emptyList(), MatchMethod.HASH)

        return if (result.isMatched) {
            MatchResult(true, result.matches, MatchMethod.HASH)
        } else {
            MatchResult(false, result.matches, MatchMethod.FILE_NAME)
        }
    }

    override suspend fun search(text: String): List<DanmuAnimeData> = searchResult(text).getOrDefault(emptyList())

    override suspend fun searchResult(text: String): Result<List<DanmuAnimeData>> {
        val result = searchRequest(text)
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        return result.map { data ->
            data.animes.filter { it.episodes.isNotEmpty() }.map { anime ->
                anime.copy(
                    episodes =
                        anime.episodes.map {
                            it.copy(animeId = anime.animeId, animeTitle = anime.animeTitle)
                        },
                )
            }
        }
    }

    override suspend fun source(episodeId: String): List<DanmuRelatedUrlData> =
        ResourceRepository
            .getRelatedDanmu(episodeId)
            .getOrNull()
            ?.relateds
            ?: emptyList()

    override suspend fun getContentByEpisodeId(
        episodeId: String,
        withRelated: Boolean
    ): List<DanmuContentData> =
        ResourceRepository
            .getDanmuContent(episodeId, withRelated)
            .getOrNull()
            ?.comments
            ?: emptyList()

    override suspend fun getContentByUrl(url: String): List<DanmuContentData> =
        ResourceRepository
            .getRelatedDanmuContent(url)
            .getOrNull()
            ?.comments
            ?: emptyList()
}
