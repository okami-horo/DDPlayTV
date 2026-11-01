package com.xyoye.common_component.metadata

import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.services.PlaybackMetadataFile
import com.xyoye.common_component.storage.catalog.CatalogSourceReader
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MetadataScanDirectory
import com.xyoye.data_component.entity.MetadataScanRun
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
    private val automatic by lazy { AutomaticMetadataRecognizer(repository) }
    private val playbackIndexer by lazy { PlaybackMetadataIndexer(repository) }

    fun startPlayback(
        version: PlaybackSessionVersion,
        input: PlaybackMetadataFile
    ): String =
        synchronized(scans) {
            val name = MediaNameParser.parse(input.file.displayTitle.orEmpty())
            val group =
                if (name.kind == "tv" && !input.parentPath.isNullOrBlank()) {
                    "${input.parentPath}|${MediaMatcher.normalize(name.title)}|${name.year}|${name.explicitId}"
                } else {
                    input.file.uniqueKey
                }
            val key = "playback|${input.source}|$group"
            scanKeys[key]?.let { return@synchronized it }
            val id = "${version.sessionId}:${UUID.randomUUID()}"
            val job = scope.launch(start = CoroutineStart.LAZY) { runPlayback(input, id, false) }
            scans[id] = job
            scanKeys[key] = id
            job.invokeOnCompletion {
                synchronized(scans) {
                    scans.remove(id)
                    scanKeys.remove(key)
                }
            }
            job.start()
            id
        }

    suspend fun recognizeAgain(assetId: Long) {
        val asset = repository.dao.asset(assetId) ?: throw StaleMetadataResult()
        val source = repository.source(asset.libraryId)
        if (source.contentGeneration != asset.contentGeneration) throw StaleMetadataResult()
        val library = repository.database.getMediaLibraryDao().getById(asset.libraryId) ?: throw StaleMetadataResult()
        val input = PlaybackMetadataFile(source, library.mediaType, reference(asset), asset.parentPath.takeIf { it.isNotBlank() })
        runPlayback(input, UUID.randomUUID().toString(), true)
    }

    suspend fun retry(run: MetadataScanRun) {
        if (run.triggerKey == null) {
            startScan(run.libraryId, run.directory, run.recursive)
            return
        }
        val source = repository.source(run.libraryId)
        if (source.contentGeneration != run.contentGeneration) throw StaleMetadataResult()
        val library = repository.database.getMediaLibraryDao().getById(run.libraryId) ?: throw StaleMetadataResult()
        val input =
            PlaybackMetadataFile(
                source,
                library.mediaType,
                PlaybackFileReference(requireNotNull(run.triggerKey), requireNotNull(run.triggerPath), displayTitle = run.triggerName),
                run.directory.takeIf {
                    it.isNotBlank()
                }
            )
        runPlayback(input, UUID.randomUUID().toString(), true)
    }

    private suspend fun runPlayback(
        input: PlaybackMetadataFile,
        id: String,
        force: Boolean
    ) {
        ready.join()
        val queued =
            MetadataScanRun(
                id,
                input.source.libraryId,
                input.source.contentGeneration,
                input.source.revision,
                input.parentPath.orEmpty(),
                false,
                "QUEUED",
                startedAt = System.currentTimeMillis(),
                triggerKey = input.file.uniqueKey,
                triggerPath = input.file.storagePath,
                triggerName = input.file.displayTitle
            )
        repository.dao.putScan(queued)
        val owner = requireNotNull(currentCoroutineContext()[Job])
        if (force) synchronized(scans) { scans[id] = owner }
        val watcher =
            scope.launch {
                repository.dao.observeSource(input.source.libraryId).first { it != input.source }
                owner.cancel(CancellationException("SOURCE_CHANGED"))
            }
        try {
            val lock = scanLock.withLock { sourceLocks.getOrPut(input.source.libraryId) { Mutex() } }
            lock.withLock {
                repository.requireSource(input.source)
                repository.dao.putScan(queued.copy(state = "RUNNING"))
                playbackIndexer.process(input, id, force)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                repository.dao.putScan((repository.dao.scan(id) ?: queued).copy(state = "CANCELLED", failure = "INTERRUPTED"))
            }
            throw cancelled
        } catch (error: Exception) {
            repository.dao.putScan((repository.dao.scan(id) ?: queued).copy(state = "FAILED", failure = failureCategory(error)))
            if (force) throw error
        } finally {
            watcher.cancel()
            if (force) synchronized(scans) { scans.remove(id) }
        }
    }

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
                    Result.failure(cancelled)
                } catch (error: Exception) {
                    Result.failure(error)
                }
            withContext(NonCancellable + Dispatchers.Main) { result(outcome) }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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
                    if (binding?.state != "IGNORED" &&
                        (binding?.failure == "FILE_CHANGED" || binding?.locked != true && binding?.state != "MATCHED")
                    ) {
                        when (automatic.recognize(asset, force = true)) {
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
            is CancellationException -> "INTERRUPTED"
            is SecurityException -> "PERMISSION"
            else -> "SOURCE_OR_DATABASE"
        }
}
