package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.danmu.helper.DanmuContentGenerator
import com.xyoye.common_component.utils.danmu.helper.DanmuFileCreator
import com.xyoye.common_component.utils.danmu.query.DanmuQuery
import com.xyoye.common_component.utils.danmu.source.DanmuSource
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.MatchResult
import org.apache.commons.io.FileUtils
import java.io.InputStream

/**
 * Created by xyoye on 2024/1/14.
 */

class DanmuFinderImpl(
    private val danmuQuery: DanmuQuery
) : DanmuFinder {
    override suspend fun getMatched(source: DanmuSource): MatchResult? {
        val hash = source.hash() ?: return null
        val result =
            danmuQuery.match(
                hash = hash,
                fileName = source.fileName(),
                fileSize = source.fileSize() ?: 0L,
                videoDuration = ((source.durationMs() ?: 0L) / 1000).toInt(),
            )
        return if (result.episodes.isEmpty()) null else result
    }

    override suspend fun downloadMatched(source: DanmuSource): LocalDanmuBean? {
        val result = getMatched(source) ?: return null
        if (result.isMatched.not()) return null
        val episode = result.episodes.firstOrNull() ?: return null
        return downloadEpisode(episode)
    }

    override suspend fun downloadEpisode(
        episode: DanmuEpisodeData,
        withRelated: Boolean
    ): LocalDanmuBean? {
        val contents = danmuQuery.getContentByEpisodeId(episode.episodeId, withRelated)
        if (contents.isEmpty()) {
            val extraInfo =
                buildString {
                    append("Anime: ${episode.animeTitle}\n")
                    append("Episode: ${episode.episodeTitle}\n")
                    append("Episode ID: ${episode.episodeId}\n")
                    append("With Related: $withRelated\n")
                    append("Error: No danmu content found from query")
                }
            ErrorReportHelper.postException(
                "Danmu content query returned empty",
                "DanmuFinderImpl.downloadEpisode",
                RuntimeException("Empty content for episode: ${episode.episodeTitle}, extra info: $extraInfo"),
            )
            return null
        }

        val xmlContent = DanmuContentGenerator.generate(contents)
        if (xmlContent == null) {
            val extraInfo =
                buildString {
                    append("Anime: ${episode.animeTitle}\n")
                    append("Episode: ${episode.episodeTitle}\n")
                    append("Episode ID: ${episode.episodeId}\n")
                    append("With Related: $withRelated\n")
                    append("Content Count: ${contents.size}\n")
                    append("Error: XML content generation failed")
                }
            ErrorReportHelper.postException(
                "Danmu XML generation failed",
                "DanmuFinderImpl.downloadEpisode",
                RuntimeException("XML generation failed for episode: ${episode.episodeTitle}, extra info: $extraInfo"),
            )
            return null
        }

        val file = DanmuFileCreator.create(episode.animeTitle, episode.episodeTitle)
        if (file == null) {
            val extraInfo =
                buildString {
                    append("Anime: ${episode.animeTitle}\n")
                    append("Episode: ${episode.episodeTitle}\n")
                    append("Episode ID: ${episode.episodeId}\n")
                    append("With Related: $withRelated\n")
                    append("XML Content Length: ${xmlContent.length}\n")
                    append("Error: Failed to create danmu file")
                }
            ErrorReportHelper.postException(
                "Danmu file creation failed",
                "DanmuFinderImpl.downloadEpisode",
                RuntimeException("File creation failed for episode: ${episode.episodeTitle}, extra info: $extraInfo"),
            )
            return null
        }

        try {
            FileUtils.write(file, xmlContent, Charsets.UTF_8)
            return LocalDanmuBean(file.absolutePath, episode.episodeId)
        } catch (e: Exception) {
            val extraInfo =
                buildString {
                    append("Anime: ${episode.animeTitle}\n")
                    append("Episode: ${episode.episodeTitle}\n")
                    append("Episode ID: ${episode.episodeId}\n")
                    append("With Related: $withRelated\n")
                    append("Content Count: ${contents.size}\n")
                    append("XML Content Length: ${xmlContent.length}\n")
                    append("Target File: ${file.absolutePath}\n")
                    append("File Exists: ${file.exists()}\n")
                    append("File Writable: ${file.canWrite()}\n")
                    append("Parent Directory: ${file.parent}\n")
                    append("Free Space: ${FileUtils.byteCountToDisplaySize(FileUtils.sizeOfDirectory(file.parentFile))}\n")
                    append("Exception: ${e.javaClass.simpleName}: ${e.message}")
                }
            ErrorReportHelper.postCatchedException(
                e,
                "DanmuFinderImpl.downloadEpisode",
                "下载弹幕失败: ${episode.animeTitle} - ${episode.episodeTitle}, extra info: $extraInfo",
            )
        }

        return null
    }

    override suspend fun search(text: String): List<DanmuAnimeData> = danmuQuery.search(text)

    override suspend fun searchResult(text: String): Result<List<DanmuAnimeData>> = danmuQuery.searchResult(text)

    override suspend fun saveStream(
        episode: DanmuEpisodeData,
        inputStream: InputStream
    ): LocalDanmuBean? {
        val file =
            DanmuFileCreator.create(episode.animeTitle, episode.episodeTitle)
                ?: return null

        try {
            FileUtils.copyToFile(inputStream, file)
            return LocalDanmuBean(file.absolutePath, episode.episodeId)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedException(
                e,
                "DanmuFinderImpl.saveStream",
                "保存弹幕流失败: ${episode.animeTitle} - ${episode.episodeTitle}",
            )
        }
        return null
    }
}
