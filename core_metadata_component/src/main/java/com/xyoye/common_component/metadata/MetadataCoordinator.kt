package com.xyoye.common_component.metadata

import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.storage.catalog.CatalogSourceReader
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MetadataEpisode
import com.xyoye.data_component.entity.MetadataScanDirectory
import com.xyoye.data_component.entity.MetadataScanRun
import com.xyoye.data_component.entity.MetadataWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Application lifetime owns confirmed mutations and scans; views own only search/preview jobs. */
object MetadataCoordinator {
    val repository by lazy { MetadataRepository() }
    private val reader = CatalogSourceReader()
    private val failure = MutableStateFlow<String?>(null)
    val taskFailure = failure.asStateFlow()
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failure.value = failureCategory(error) }
        )
    private val scanLock = Mutex()
    private val sourceLocks = mutableMapOf<Int, Mutex>()
    private val scans = mutableMapOf<String, Job>()
    private val scanKeys = mutableMapOf<String, String>()
    private val ready by lazy { scope.launch { repository.dao.interruptScans() } }

    suspend fun prepare(
        libraryId: Int,
        reference: PlaybackFileReference
    ): BindingTicket =
        withContext(Dispatchers.IO) {
            val source = repository.source(libraryId)
            val library = repository.database.getMediaLibraryDao().getById(libraryId) ?: throw StaleMetadataResult()
            val file = reader.file(library, reference)
            repository.begin(repository.index(file, source).assetId)
        }

    suspend fun prepare(assetId: Long): BindingTicket {
        val asset = repository.dao.asset(assetId) ?: throw StaleMetadataResult()
        return prepare(asset.libraryId, reference(asset))
    }

    suspend fun candidates(
        parsed: ParsedMediaName,
        kind: String?,
        query: String,
        page: Int = 1
    ): Pair<List<MediaCandidate>, Boolean> {
        val kinds = kind?.let(::listOf) ?: listOf("movie", "tv")
        val results = mutableListOf<MediaCandidate>()
        var more = false
        for (type in kinds) {
            if (parsed.explicitId != null && query == parsed.title) {
                try {
                    val work = repository.details(type, parsed.explicitId)
                    results +=
                        MediaCandidate(
                            type,
                            com.xyoye.data_component.data.tmdb.TmdbTitle(
                                work.tmdbId,
                                title = work.title,
                                originalTitle = work.originalTitle,
                                releaseDate = work.releaseDate,
                                posterPath = work.poster
                            )
                        )
                } catch (error: TmdbFailure) {
                    if (error.category != "NOT_FOUND") throw error
                }
            } else {
                var response = repository.search(type, query, parsed.year, page)
                if (page == 1 && response.results.isEmpty() && parsed.year != null) response = repository.search(type, query, null)
                results += response.results.map { MediaCandidate(type, it) }
                more = more || response.page < response.totalPages
            }
        }
        return MediaMatcher.rank(parsed, results) to more
    }

    suspend fun confirm(
        ticket: BindingTicket,
        work: MetadataWork,
        episodes: List<MetadataEpisode>
    ) = repository.bind(ticket, work, episodes, manual = true)

    fun submit(
        operation: suspend () -> Unit,
        result: (Result<Unit>) -> Unit
    ): Job =
        scope.launch {
            val outcome =
                try {
                    operation()
                    Result.success(Unit)
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            withContext(Dispatchers.Main) { result(outcome) }
        }

    suspend fun refresh(ticket: BindingTicket) {
        val kind = requireNotNull(ticket.binding.mediaKind) { "NO_BINDING" }
        val id = requireNotNull(ticket.binding.tmdbId) { "NO_BINDING" }
        try {
            repository.details(kind, id, refresh = true)
            repository.dao
                .bindingEpisodes(ticket.asset.assetId)
                .map { it.seasonNumber }
                .distinct()
                .forEach { repository.season(id, it, refresh = true) }
            repository.recordResult(ticket, ticket.binding.state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            repository.recordFailure(ticket, failureCategory(error))
            throw error
        }
    }

    fun startScan(
        libraryId: Int,
        directory: String,
        recursive: Boolean = true
    ): String =
        synchronized(scans) {
            val key = "$libraryId|${directory.trimEnd('/')}|$recursive"
            scanKeys[key]?.let { return@synchronized it }
            val runId = UUID.randomUUID().toString()
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    ready.join()
                    val source = repository.source(libraryId)
                    val queued =
                        MetadataScanRun(
                            runId,
                            libraryId,
                            source.contentGeneration,
                            source.revision,
                            directory,
                            recursive,
                            "QUEUED",
                            startedAt = System.currentTimeMillis()
                        )
                    repository.dao.putScan(queued)
                    val owner = requireNotNull(currentCoroutineContext()[Job])
                    val watcher =
                        scope.launch {
                            repository.dao.observeSource(libraryId).first { it != source }
                            owner.cancel(CancellationException("SOURCE_CHANGED"))
                        }
                    try {
                        val lock = scanLock.withLock { sourceLocks.getOrPut(libraryId) { Mutex() } }
                        lock.withLock {
                            repository.requireSource(source)
                            scan(runId, libraryId, directory, recursive)
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            val current = repository.dao.scan(runId) ?: queued
                            repository.dao.putScan(current.copy(state = "CANCELLED"))
                        }
                        throw cancelled
                    } catch (error: Exception) {
                        val current = repository.dao.scan(runId) ?: queued
                        repository.dao.putScan(current.copy(state = "FAILED", failure = failureCategory(error)))
                    } finally {
                        watcher.cancel()
                    }
                }
            scans[runId] = job
            scanKeys[key] = runId
            job.invokeOnCompletion {
                synchronized(scans) {
                    scans.remove(runId)
                    scanKeys.remove(key)
                }
            }
            job.start()
            runId
        }

    fun cancel(runId: String) {
        synchronized(scans) { scans[runId] }?.cancel()
    }

    fun initialize() {
        ready
    }

    private suspend fun scan(
        runId: String,
        libraryId: Int,
        directory: String,
        recursive: Boolean
    ) {
        val source = repository.source(libraryId)
        val library = repository.database.getMediaLibraryDao().getById(libraryId) ?: return
        var run =
            MetadataScanRun(
                runId,
                libraryId,
                source.contentGeneration,
                source.revision,
                directory,
                recursive,
                "RUNNING",
                startedAt = System.currentTimeMillis()
            )
        repository.dao.putScan(run)
        try {
            val pending = ArrayDeque<String>().apply { add(directory) }
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                repository.requireSource(source)
                val path = pending.removeFirst()
                if (!visited.add(path) || visited.size > 10_000) throw TmdbFailure("DIRECTORY_CYCLE")
                repository.dao.putDirectory(MetadataScanDirectory(runId, path))
                val snapshot = reader.directory(library, path)
                for (file in snapshot.files) {
                    currentCoroutineContext().ensureActive()
                    val asset = repository.index(file, source, runId)
                    val binding = repository.dao.binding(asset.assetId)
                    run = run.copy(discovered = run.discovered + 1)
                    if (binding?.locked != true &&
                        binding?.state != "MATCHED" &&
                        binding?.state != "IGNORED" &&
                        binding?.failure != "FILE_CHANGED"
                    ) {
                        when (recognize(asset)) {
                            "MATCHED" -> run = run.copy(matched = run.matched + 1)
                            "FAILED" -> run = run.copy(failed = run.failed + 1)
                            else -> run = run.copy(pending = run.pending + 1)
                        }
                    } else if (binding.state == "MATCHED") {
                        run = run.copy(matched = run.matched + 1)
                    } else {
                        run = run.copy(pending = run.pending + 1)
                    }
                    repository.dao.putScan(run)
                }
                repository.completeDirectory(source, snapshot, runId)
                run = run.copy(completedDirectories = run.completedDirectories + 1)
                repository.dao.putScan(run)
                if (recursive) pending.addAll(snapshot.directories)
            }
            run = run.copy(state = "COMPLETED")
        } catch (cancelled: CancellationException) {
            run = run.copy(state = "CANCELLED")
            throw cancelled
        } catch (error: Exception) {
            run = run.copy(state = "FAILED", failure = failureCategory(error))
        } finally {
            withContext(NonCancellable) { repository.dao.putScan(run) }
        }
    }

    private suspend fun recognize(asset: MediaAsset): String {
        val ticket = repository.begin(asset.assetId)
        try {
            val parsed = MediaNameParser.parse(asset.fileName, asset.parentPath)
            if (parsed.extra) {
                repository.recordResult(ticket, "NEEDS_CONFIRMATION")
                return "NEEDS_CONFIRMATION"
            }
            val candidates = candidates(parsed, parsed.kind, parsed.title).first
            val automatic = MediaMatcher.automatic(parsed, candidates)
            if (automatic == null) {
                repository.recordResult(ticket, if (candidates.isEmpty()) "UNMATCHED" else "NEEDS_CONFIRMATION")
                return "NEEDS_CONFIRMATION"
            }
            val work = repository.details(automatic.kind, automatic.title.id)
            val episodes =
                if (work.mediaKind == "tv") {
                    val season = repository.season(work.tmdbId, requireNotNull(parsed.season))
                    if (!parsed.episodes.all { number -> season.any { it.episodeNumber == number } }) {
                        repository.recordResult(ticket, "NEEDS_CONFIRMATION")
                        return "NEEDS_CONFIRMATION"
                    }
                    season.filter { it.episodeNumber in parsed.episodes }
                } else {
                    emptyList()
                }
            repository.bind(ticket, work, episodes, manual = false)
            return "MATCHED"
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (stale: StaleMetadataResult) {
            throw stale
        } catch (error: Exception) {
            repository.recordResult(ticket, "UNMATCHED", failureCategory(error))
            // Authentication is shared by every file, so stop the scan instead of retrying each item.
            if (error is TmdbFailure && error.category in setOf("AUTHENTICATION", "NOT_CONFIGURED", "RATE_LIMIT")) throw error
            return "FAILED"
        }
    }

    fun reference(asset: MediaAsset): PlaybackFileReference =
        PlaybackFileReference(
            asset.uniqueKey,
            asset.storagePath,
            protocolReference = asset.protocolReference,
            displayTitle = asset.fileName
        )

    fun failureCategory(error: Throwable): String =
        when (error) {
            is TmdbFailure -> error.category
            is StaleMetadataResult -> "RESULT_EXPIRED"
            is SecurityException -> "PERMISSION"
            else -> "SOURCE_OR_DATABASE"
        }
}
