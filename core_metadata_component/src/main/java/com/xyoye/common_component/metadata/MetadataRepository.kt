package com.xyoye.common_component.metadata

import androidx.room.withTransaction
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.network.service.TmdbService
import com.xyoye.common_component.network.tmdb.TmdbClient
import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.common_component.storage.catalog.CatalogDirectorySnapshot
import com.xyoye.common_component.storage.catalog.CatalogFileSnapshot
import com.xyoye.data_component.data.tmdb.TmdbImageConfiguration
import com.xyoye.data_component.data.tmdb.TmdbPage
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaBindingEpisode
import com.xyoye.data_component.entity.MetadataEpisode
import com.xyoye.data_component.entity.MetadataScanDirectory
import com.xyoye.data_component.entity.MetadataSeason
import com.xyoye.data_component.entity.MetadataWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class BindingTicket(
    val source: CatalogSourceState,
    val asset: MediaAsset,
    val binding: MediaBinding
)

data class DirectoryMapping(
    val ticket: BindingTicket,
    val episodes: List<MetadataEpisode>,
    val conflict: String?
)

class StaleMetadataResult : IllegalStateException("RESULT_EXPIRED")

/** Durable catalog state. All asset mutations compare source, file and binding generations in a transaction. */
class MetadataRepository(
    val database: DatabaseInfo = DatabaseProvider.instance,
    private val service: TmdbService = TmdbClient.service
) {
    val dao = database.getMetadataDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = LinkedHashMap<String, Pair<Long, Deferred<Any>>>()
    private val requestLock = Mutex()
    private val generations = AtomicLong(System.currentTimeMillis())
    private val resourceLock = Mutex()
    private val latestResources = mutableMapOf<String, Long>()

    suspend fun source(id: Int): CatalogSourceState =
        database.withTransaction {
            if (database.getMediaLibraryDao().getById(id) == null) throw StaleMetadataResult()
            dao.source(id) ?: CatalogSourceState(id).also { dao.putSource(it) }
        }

    suspend fun index(
        snapshot: CatalogFileSnapshot,
        expected: CatalogSourceState,
        runId: String? = null
    ): MediaAsset =
        database.withTransaction {
            requireSource(expected)
            val old = dao.asset(expected.libraryId, expected.contentGeneration, snapshot.locatorKey)
            val changed =
                old != null &&
                    (
                        old.uniqueKey != snapshot.reference.uniqueKey ||
                            (old.size != null && snapshot.size != null && old.size != snapshot.size) ||
                            (old.modifiedAt != null && snapshot.modifiedAt != null && old.modifiedAt != snapshot.modifiedAt)
                    )
            val asset =
                MediaAsset(
                    old?.assetId ?: 0,
                    expected.libraryId,
                    expected.contentGeneration,
                    snapshot.locatorKey,
                    snapshot.reference.uniqueKey,
                    snapshot.reference.storagePath,
                    snapshot.reference.protocolReference,
                    snapshot.fileName,
                    snapshot.parentPath.ifBlank { old?.parentPath.orEmpty() },
                    snapshot.size,
                    snapshot.modifiedAt,
                    old?.discoveredAt ?: System.currentTimeMillis(),
                    true,
                    (old?.revision ?: 1) + if (changed) 1 else 0,
                    runId ?: old?.lastSeenRun
                )
            val saved = asset.copy(assetId = dao.putAsset(asset))
            val binding = dao.binding(saved.assetId)
            if (binding == null) {
                dao.putBinding(MediaBinding(saved.assetId))
            } else if (changed) {
                dao.putBinding(
                    binding.copy(
                        state = "NEEDS_CONFIRMATION",
                        revision = binding.revision + 1,
                        taskId = null,
                        failure = "FILE_CHANGED"
                    )
                )
            }
            saved
        }

    suspend fun begin(assetId: Long): BindingTicket =
        database.withTransaction {
            val asset = dao.asset(assetId) ?: throw StaleMetadataResult()
            val source = source(asset.libraryId)
            if (source.contentGeneration != asset.contentGeneration) throw StaleMetadataResult()
            val binding =
                (dao.binding(assetId) ?: MediaBinding(assetId)).let {
                    it.copy(revision = it.revision + 1, taskId = UUID.randomUUID().toString())
                }
            dao.putBinding(binding)
            BindingTicket(source, asset, binding)
        }

    suspend fun previewDirectory(
        ticket: BindingTicket,
        work: MetadataWork,
        season: Int,
        firstEpisode: Int
    ): List<DirectoryMapping> {
        require(work.mediaKind == "tv")
        require(ticket.asset.parentPath.isNotBlank()) { "DIRECTORY_NOT_INDEXED" }
        val parsed = MediaNameParser.parse(ticket.asset.fileName, ticket.asset.parentPath)
        val originalNumber =
            parsed.absoluteEpisode ?: parsed.episodes.firstOrNull()
                ?: throw IllegalArgumentException("NO_EPISODE_NUMBER")
        val offset = firstEpisode - originalNumber
        val available = season(work.tmdbId, season)
        return database.withTransaction {
            requireTicket(ticket)
            dao.directoryAssets(ticket.asset.libraryId, ticket.asset.contentGeneration, ticket.asset.parentPath).map { asset ->
                val binding = dao.binding(asset.assetId) ?: MediaBinding(asset.assetId)
                val name = MediaNameParser.parse(asset.fileName, asset.parentPath)
                val numbers = name.absoluteEpisode?.let(::listOf) ?: name.episodes
                val mapped = numbers.map { it + offset }
                val selected = available.filter { it.episodeNumber in mapped }
                val conflict =
                    when {
                        binding.locked -> "已有人工关联或忽略，将跳过"
                        !asset.available || binding.failure == "FILE_CHANGED" -> "文件不可用或已变化，将跳过"
                        MediaMatcher.normalize(name.title) != MediaMatcher.normalize(parsed.title) -> "作品名称不同，将跳过"
                        numbers.isEmpty() || selected.size != mapped.size -> "无集号或目标单集不存在，将跳过"
                        name.season != null && parsed.season != name.season -> "文件季度不同，将跳过"
                        else -> null
                    }
                DirectoryMapping(BindingTicket(ticket.source, asset, binding), selected, conflict)
            }
        }
    }

    suspend fun applyDirectory(
        work: MetadataWork,
        preview: List<DirectoryMapping>
    ) = database.withTransaction {
        val accepted = preview.filter { it.conflict == null }
        accepted.forEach { requireTicket(it.ticket) }
        accepted.forEach { bind(it.ticket, work, it.episodes, manual = true) }
    }

    suspend fun completeDirectory(
        source: CatalogSourceState,
        snapshot: CatalogDirectorySnapshot,
        runId: String
    ) = database.withTransaction {
        requireSource(source)
        if (!snapshot.complete) throw TmdbFailure("DIRECTORY_INCOMPLETE")
        dao.markMissing(source.libraryId, source.contentGeneration, snapshot.path, runId)
        dao.putDirectory(MetadataScanDirectory(runId, snapshot.path, true))
    }

    suspend fun bind(
        ticket: BindingTicket,
        work: MetadataWork,
        episodes: List<MetadataEpisode>,
        manual: Boolean
    ) {
        database.withTransaction {
            requireTicket(ticket)
            if (!manual && (ticket.binding.locked || ticket.binding.state == "IGNORED" || ticket.binding.failure == "FILE_CHANGED")) {
                throw StaleMetadataResult()
            }
            require(work.mediaKind == "movie" && episodes.isEmpty() || work.mediaKind == "tv")
            require(episodes.all { it.seriesId == work.tmdbId && it.seasonNumber >= 0 && it.episodeNumber >= 0 })
            saveWork(work)
            // Candidate selection owns bindings, never a newer shared season snapshot.
            dao.putNewEpisodes(episodes)
            dao.clearEpisodes(ticket.asset.assetId)
            dao.putBindingEpisodes(
                episodes.mapIndexed { index, episode ->
                    MediaBindingEpisode(ticket.asset.assetId, episode.seasonNumber, episode.episodeNumber, index)
                }
            )
            dao.putBinding(
                ticket.binding.copy(
                    state = if (work.mediaKind == "tv" && episodes.isEmpty()) "NEEDS_CONFIRMATION" else "MATCHED",
                    mediaKind = work.mediaKind,
                    tmdbId = work.tmdbId,
                    origin = if (manual) "MANUAL" else "AUTO",
                    locked = manual,
                    revision = ticket.binding.revision + 1,
                    taskId = null,
                    evidence = if (manual) "USER_CONFIRMED" else "EXACT_ID_OR_TITLE_YEAR",
                    failure = null
                )
            )
        }
    }

    suspend fun ignore(ticket: BindingTicket) =
        database.withTransaction {
            requireTicket(ticket)
            dao.clearEpisodes(ticket.asset.assetId)
            dao.putBinding(
                MediaBinding(
                    ticket.asset.assetId,
                    state = "IGNORED",
                    locked = true,
                    origin = "MANUAL",
                    revision =
                        ticket.binding.revision + 1
                )
            )
        }

    suspend fun recordResult(
        ticket: BindingTicket,
        state: String,
        failure: String? = null
    ) = database.withTransaction {
        requireTicket(ticket)
        dao.putBinding(
            ticket.binding.copy(
                state = if (ticket.binding.tmdbId != null || ticket.binding.state == "IGNORED") ticket.binding.state else state,
                failure = failure,
                revision = ticket.binding.revision + 1,
                taskId = null
            )
        )
    }

    suspend fun requireSource(expected: CatalogSourceState) {
        if (database.getMediaLibraryDao().getById(expected.libraryId) == null || dao.source(expected.libraryId) != expected) {
            throw StaleMetadataResult()
        }
    }

    suspend fun recordFailure(
        ticket: BindingTicket,
        category: String
    ) = database.withTransaction {
        requireTicket(ticket)
        dao.putBinding(ticket.binding.copy(failure = category))
    }

    private suspend fun requireTicket(ticket: BindingTicket) {
        requireSource(ticket.source)
        val current = dao.asset(ticket.asset.assetId) ?: throw StaleMetadataResult()
        val binding = dao.binding(current.assetId) ?: throw StaleMetadataResult()
        if (current.revision != ticket.asset.revision ||
            current.contentGeneration != ticket.asset.contentGeneration ||
            binding.revision != ticket.binding.revision ||
            binding.taskId != ticket.binding.taskId
        ) {
            throw StaleMetadataResult()
        }
    }

    suspend fun search(
        kind: String,
        query: String,
        year: Int?,
        page: Int = 1
    ): TmdbPage {
        require(kind == "movie" || kind == "tv")
        // Candidate retries always reach the service; HTTP caching still follows response headers/Vary.
        return TmdbClient.request {
            service.search(
                kind,
                query,
                "zh-CN",
                page,
                year.takeIf { kind == "movie" },
                year.takeIf { kind == "tv" }
            )
        }
    }

    suspend fun details(
        kind: String,
        id: Long,
        refresh: Boolean = false
    ): MetadataWork {
        require(kind == "movie" || kind == "tv")
        if (!refresh) dao.work(kind, id)?.let { if (it.language == "zh-CN") return it }
        val resource = "work/$kind/$id"
        val generation = resourceLock.withLock { generations.incrementAndGet().also { latestResources[resource] = it } }
        val primary = shared("details/$kind/$id/zh-CN", refresh) { service.details(kind, id, "zh-CN") }
        if (primary.id != id) throw TmdbFailure("INVALID_RESPONSE")
        val fallback =
            if (primary.displayTitle().isBlank() || primary.overview.isNullOrBlank()) {
                try {
                    shared("details/$kind/$id/en-US", refresh) { service.details(kind, id, "en-US") }
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (_: TmdbFailure) {
                    null
                }
            } else {
                null
            }
        val images = images()
        val work =
            MetadataWork(
                kind,
                id,
                primary.displayTitle().ifBlank { primary.original().ifBlank { fallback?.displayTitle().orEmpty() } },
                primary.original(),
                primary.overview?.takeIf { it.isNotBlank() } ?: fallback?.overview.orEmpty(),
                primary.date(),
                primary.genres.joinToString(" · ") { it.name },
                primary.voteAverage,
                image(images, primary.posterPath, "poster"),
                image(images, primary.backdropPath, "backdrop"),
                "zh-CN",
                if (fallback != null) "missing_text:en-US" else "",
                System.currentTimeMillis(),
                generation
            )
        resourceLock.withLock {
            database.withTransaction {
                val previous = dao.work(kind, id)
                if (latestResources[resource] == generation && (previous == null || previous.requestGeneration <= generation)) {
                    dao.putWork(work)
                    if (kind == "tv") {
                        dao.putSeasons(
                            primary.seasons.map {
                                MetadataSeason(
                                    id,
                                    it.seasonNumber,
                                    it.name.orEmpty(),
                                    image(images, it.posterPath, "poster"),
                                    it.airDate.orEmpty()
                                )
                            }
                        )
                    }
                }
            }
        }
        return work
    }

    suspend fun season(
        id: Long,
        number: Int,
        refresh: Boolean = false
    ): List<MetadataEpisode> {
        require(number >= 0)
        val existing = dao.episodes(id, number)
        if (!refresh && existing.isNotEmpty()) return existing
        val resource = "season/$id/$number"
        val generation = resourceLock.withLock { generations.incrementAndGet().also { latestResources[resource] = it } }
        val data = shared("season/$id/$number/zh-CN", refresh) { service.season(id, number, "zh-CN") }
        if (data.seasonNumber != number || data.episodes.any { it.seasonNumber != number }) throw TmdbFailure("INVALID_RESPONSE")
        val fallback =
            if (data.episodes.any { it.name.isNullOrBlank() || it.overview.isNullOrBlank() }) {
                try {
                    shared("season/$id/$number/en-US", refresh) { service.season(id, number, "en-US") }
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (_: TmdbFailure) {
                    null
                }
            } else {
                null
            }
        val images = images()
        val episodes =
            data.episodes.map { episode ->
                val other = fallback?.episodes?.find { it.episodeNumber == episode.episodeNumber }
                MetadataEpisode(
                    id,
                    number,
                    episode.episodeNumber,
                    episode.id,
                    episode.name?.takeIf { it.isNotBlank() } ?: other?.name.orEmpty(),
                    episode.overview?.takeIf { it.isNotBlank() } ?: other?.overview.orEmpty(),
                    episode.airDate.orEmpty(),
                    image(images, episode.stillPath, "still")
                )
            }
        resourceLock.withLock {
            database.withTransaction {
                if (latestResources[resource] == generation) {
                    dao.putEpisodes(episodes)
                    dao.putSeasons(
                        listOf(
                            MetadataSeason(
                                id,
                                number,
                                data.name.orEmpty(),
                                image(images, data.posterPath, "poster"),
                                data.airDate.orEmpty(),
                                System.currentTimeMillis()
                            )
                        )
                    )
                }
            }
        }
        return episodes
    }

    private suspend fun saveWork(work: MetadataWork) {
        val existing = dao.work(work.mediaKind, work.tmdbId)
        if (existing == null || existing.requestGeneration <= work.requestGeneration) dao.putWork(work)
    }

    private suspend fun images(): TmdbImageConfiguration? =
        try {
            shared("configuration", false) { service.configuration() }.images
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TmdbFailure) {
            null
        }

    suspend fun poster(path: String?): String? =
        if (path?.startsWith("https://image.tmdb.org/") ==
            true
        ) {
            path
        } else {
            image(images(), path, "poster")
        }

    private fun image(
        config: TmdbImageConfiguration?,
        path: String?,
        type: String
    ): String? {
        if (config == null ||
            path.isNullOrBlank() ||
            !path.startsWith('/') ||
            !config.baseUrl.startsWith("https://image.tmdb.org/")
        ) {
            return null
        }
        val sizes =
            when (type) {
                "poster" -> config.posterSizes
                "backdrop" -> config.backdropSizes
                else -> config.stillSizes
            }
        val target =
            when (type) {
                "poster" -> 500
                "backdrop" -> 1280
                else -> 300
            }
        val size =
            sizes.filter { it.startsWith('w') }.minByOrNull { kotlin.math.abs((it.drop(1).toIntOrNull() ?: Int.MAX_VALUE) - target) }
                ?: return null
        return config.baseUrl + size + path
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> shared(
        key: String,
        refresh: Boolean,
        block: suspend () -> T
    ): T {
        val deferred =
            requestLock.withLock {
                val old = requests[key]
                if (!refresh && old != null && System.currentTimeMillis() - old.first < 600_000 && !old.second.isCancelled) {
                    old.second
                } else {
                    scope.async<Any> { TmdbClient.request(block) }.also {
                        requests[key] = System.currentTimeMillis() to it
                        while (requests.size > 64) requests.remove(requests.keys.first())
                    }
                }
            }
        // Waiting callers do not own the shared request's lifetime.
        return deferred.await() as T
    }
}
