package com.xyoye.common_component.metadata

import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.data_component.data.tmdb.TmdbTitle
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MetadataWork
import kotlinx.coroutines.CancellationException

/** Shared by explicit scans and playback. Ambiguous or truncated searches never select the first hit. */
class AutomaticMetadataRecognizer(
    private val repository: MetadataRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun recognize(
        asset: MediaAsset,
        force: Boolean = false,
        sameWork: MetadataWork? = null
    ): String {
        val previous = repository.dao.binding(asset.assetId)
        val changed = previous?.evidence == "INPUT_CHANGED" || previous?.failure == "FILE_CHANGED"
        if (!force && previous != null) {
            if (previous.state == "IGNORED" || (!changed && (previous.locked || previous.state == "MATCHED"))) return previous.state
            if (previous.ruleVersion == MediaNameParser.RULE_VERSION && previous.failure != null && previous.failure != "FILE_CHANGED") {
                val transient =
                    previous.failure in
                        setOf("NETWORK", "SERVER", "RATE_LIMIT", "AUTHENTICATION", "NOT_CONFIGURED", "SOURCE_OR_DATABASE", "PERMISSION")
                if (!transient || now() - previous.attemptedAt < 300_000) return if (transient) "FAILED" else previous.state
            }
        }
        val ticket = repository.begin(asset.assetId)

        suspend fun unresolved(reason: String): String {
            repository.recordResult(ticket, "NEEDS_CONFIRMATION", reason)
            return "NEEDS_CONFIRMATION"
        }
        try {
            val parsed = MediaNameParser.parse(asset.fileName, asset.parentPath)
            if (parsed.extra) return unresolved("EXTRA_FILE")
            if (parsed.title.isBlank()) return unresolved("NAME_INSUFFICIENT")
            if (parsed.absoluteEpisode != null) return unresolved("ABSOLUTE_EPISODE")
            if (parsed.kind == "tv" && (parsed.season == null || parsed.episodes.isEmpty())) return unresolved("EPISODE_REQUIRED")
            val candidates = mutableListOf<MediaCandidate>()
            if (sameWork != null) {
                candidates += candidate(sameWork)
            } else {
                for (kind in parsed.kind?.let(::listOf) ?: listOf("movie", "tv")) {
                    if (parsed.explicitId != null) {
                        try {
                            candidates += candidate(repository.details(kind, parsed.explicitId))
                        } catch (failure: TmdbFailure) {
                            if (failure.category != "NOT_FOUND") throw failure
                        }
                    } else {
                        for (page in 1..3) {
                            val response = repository.search(kind, parsed.title, parsed.year, page)
                            candidates += response.results.map { MediaCandidate(kind, it) }
                            if (response.page >= response.totalPages) break
                            if (page == 3) return unresolved("SEARCH_LIMIT")
                        }
                    }
                }
            }
            val match =
                MediaMatcher.automatic(parsed, candidates)
                    ?: return unresolved(if (candidates.isEmpty()) "NO_RESULTS" else "AMBIGUOUS")
            val work = sameWork ?: repository.details(match.kind, match.title.id)
            val episodes =
                if (work.mediaKind == "tv") {
                    val available = repository.season(work.tmdbId, requireNotNull(parsed.season))
                    if (!parsed.episodes.all { number ->
                            available.any { it.episodeNumber == number }
                        }
                    ) {
                        return unresolved("EPISODE_MISSING")
                    }
                    available.filter { it.episodeNumber in parsed.episodes }
                } else {
                    emptyList()
                }
            repository.bind(ticket, work, episodes, manual = false, replaceExisting = force || changed)
            return "MATCHED"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stale: StaleMetadataResult) {
            throw stale
        } catch (error: Exception) {
            repository.recordResult(ticket, "UNMATCHED", MetadataCoordinator.failureCategory(error))
            if (error is TmdbFailure && error.category in setOf("AUTHENTICATION", "NOT_CONFIGURED", "RATE_LIMIT")) throw error
            return "FAILED"
        }
    }

    private fun candidate(work: MetadataWork) =
        MediaCandidate(
            work.mediaKind,
            TmdbTitle(work.tmdbId, title = work.title, originalTitle = work.originalTitle, releaseDate = work.releaseDate)
        )
}
