package com.xyoye.local_component.ui.metadata

import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBindingEpisode
import com.xyoye.data_component.entity.MetadataWork
import com.xyoye.data_component.entity.PlayHistoryEntity

/** Display/navigation projection only; progress always belongs to the original history. */
data class MetadataHistory(
    val history: PlayHistoryEntity,
    val asset: MediaAsset?,
    val kind: String?,
    val tmdbId: Long?,
    val work: MetadataWork?,
    val episodes: List<MediaBindingEpisode>
) {
    val key: String get() = if (kind != null && tmdbId != null) "$kind:$tmdbId" else "history:${history.id}"
    val episodeLabel: String
        get() {
            if (episodes.isEmpty()) return "季集待确认"
            val season = episodes.map { it.seasonNumber }.distinct().singleOrNull() ?: return "合并集"
            val numbers = episodes.map { it.episodeNumber }.distinct().sorted()
            val number =
                when {
                    numbers.size == 1 -> numbers.first().toString()
                    numbers.size <= 4 && numbers.last() - numbers.first() == numbers.size - 1 -> "${numbers.first()}–${numbers.last()}"
                    else -> return "合并集"
                }
            return "${seasonLabel(season)} · 第 $number 集"
        }
    val subtitle: String
        get() =
            when {
                tmdbId == null -> "文件记录 · ${historyProgress(history)}"
                kind == "tv" -> "$episodeLabel · ${historyProgress(history, if (episodes.size > 1) "文件" else "本集")}"
                else -> historyProgress(history)
            }

    fun card() = MetadataCard(key, work?.title ?: history.videoName, subtitle, work?.backdrop ?: work?.poster, historyPercent(history))
}

fun projectMetadataHistory(state: MetadataLibraryState): List<MetadataHistory> {
    val assets = state.assets.groupBy { it.libraryId to it.uniqueKey }
    val bindings = state.bindings.associateBy { it.assetId }
    val works = state.works.associateBy { it.mediaKind to it.tmdbId }
    val episodes = state.episodes.groupBy { it.assetId }
    return state.history.sortedByDescending { it.playTime }.map { history ->
        // Histories have no generation stamp. Never attach ambiguous/replaced content by path alone.
        val asset =
            if (history.uniqueKey.isBlank()) {
                null
            } else {
                assets[history.storageId to history.uniqueKey]?.singleOrNull()?.takeIf { it.contentGeneration == 1L }
            }
        val binding = bindings[asset?.assetId]?.takeIf { it.state == "MATCHED" && it.failure != "FILE_CHANGED" }
        val kind = binding?.mediaKind?.takeIf { it == "tv" || it == "movie" }
        val id = binding?.tmdbId?.takeIf { kind != null && it > 0 }
        MetadataHistory(
            history,
            asset.takeUnless { bindings[it?.assetId]?.failure == "FILE_CHANGED" },
            kind.takeIf { id != null },
            id,
            works[kind to id],
            if (id != null && kind == "tv") episodes[asset?.assetId].orEmpty().sortedBy { it.ordinal } else emptyList()
        )
    }
}

fun historyPercent(history: PlayHistoryEntity): Int? =
    if (history.videoDuration > 0) {
        (history.videoPosition.coerceAtLeast(0).toDouble() / history.videoDuration * 100).toInt().coerceIn(0, 100)
    } else {
        null
    }

fun historyProgress(
    history: PlayHistoryEntity,
    scope: String = "上次观看至"
): String {
    historyPercent(history)?.let { return "$scope $it%" }
    if (history.videoPosition <= 0) return "最近播放"
    val seconds = history.videoPosition / 1000
    val time =
        if (seconds >= 3600) {
            "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
    return "上次观看至 $time"
}

fun seasonLabel(season: Int): String = if (season == 0) "特别篇" else "第 $season 季"

fun initialDetailSeason(
    seasons: List<Int>,
    saved: Int?,
    recent: MetadataHistory?
): Int? =
    saved?.takeIf { it in seasons }
        ?: recent
            ?.episodes
            ?.firstOrNull()
            ?.seasonNumber
            ?.takeIf { it in seasons }
        ?: seasons.filter { it > 0 }.minOrNull()
        ?: seasons.minOrNull()
