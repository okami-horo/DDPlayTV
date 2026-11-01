package com.xyoye.common_component.metadata

import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.services.PlaybackMetadataFile
import com.xyoye.common_component.storage.catalog.CatalogDirectorySnapshot
import com.xyoye.common_component.storage.catalog.CatalogFileSnapshot
import com.xyoye.common_component.storage.catalog.CatalogSourceReader
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.MetadataScanRun
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One-level discovery only. A same-series subset must never drive missing-file cleanup. */
class PlaybackMetadataIndexer(
    private val repository: MetadataRepository,
    private val readFile: suspend (MediaLibraryEntity, PlaybackFileReference) -> CatalogFileSnapshot = CatalogSourceReader()::file,
    private val readDirectory: suspend (MediaLibraryEntity, String) -> CatalogDirectorySnapshot = CatalogSourceReader()::directory,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val recognizer = AutomaticMetadataRecognizer(repository)
    private val cacheLock = Mutex()
    private val directories = LinkedHashMap<String, Pair<Long, CatalogDirectorySnapshot>>()

    suspend fun process(
        input: PlaybackMetadataFile,
        runId: String,
        force: Boolean = false
    ) {
        repository.requireSource(input.source)
        if (!CatalogSourceReader.supports(input.mediaType)) throw TmdbFailure("SOURCE_UNSUPPORTED")
        val library = repository.database.getMediaLibraryDao().getById(input.source.libraryId) ?: throw StaleMetadataResult()
        repository.requireSource(input.source)
        val file = readFile(library, input.file)
        val asset = repository.index(file.copy(parentPath = input.parentPath ?: file.parentPath), input.source)
        var run =
            repository.dao.scan(runId)
                ?: MetadataScanRun(
                    runId,
                    library.id,
                    input.source.contentGeneration,
                    input.source.revision,
                    asset.parentPath,
                    false,
                    "RUNNING",
                    startedAt = now()
                )

        suspend fun count(result: String) {
            run =
                run.copy(
                    discovered = run.discovered + 1,
                    matched = run.matched + if (result == "MATCHED") 1 else 0,
                    failed = run.failed + if (result == "FAILED") 1 else 0,
                    pending = run.pending + if (result != "MATCHED" && result != "FAILED") 1 else 0
                )
            repository.dao.putScan(run)
        }
        count(recognizer.recognize(asset, force))
        val binding = repository.dao.binding(asset.assetId)
        run = run.copy(failure = binding?.failure)
        val work = binding?.tmdbId?.let { repository.dao.work(binding.mediaKind ?: return@let null, it) }
        if (binding?.state == "MATCHED" && binding.failure == null && work?.mediaKind == "tv") {
            val parent = asset.parentPath
            if (parent.isBlank()) {
                repository.dao.putScan(run.copy(state = "PARTIAL", failure = "PARENT_UNAVAILABLE"))
                return
            }
            val parsed = MediaNameParser.parse(asset.fileName)
            val key = "${input.source}|$parent"
            val snapshot =
                cacheLock.withLock {
                    val cached = directories[key]
                    if (!force &&
                        cached != null &&
                        now() - cached.first in 0..59_999 &&
                        cached.second.files.any {
                            it.reference.uniqueKey == file.reference.uniqueKey &&
                                it.fileName == file.fileName &&
                                it.size == file.size &&
                                it.modifiedAt == file.modifiedAt
                        }
                    ) {
                        cached.second
                    } else {
                        readDirectory(library, parent).also {
                            if (it.complete) directories[key] = now() to it
                            while (directories.size > 16) directories.remove(directories.keys.first())
                        }
                    }
                }
            repository.requireSource(input.source)
            if (snapshot.files.none { it.reference.uniqueKey == file.reference.uniqueKey }) {
                repository.dao.putScan(run.copy(state = "PARTIAL", failure = "PARENT_UNAVAILABLE"))
                return
            }
            for (sibling in snapshot.files) {
                currentCoroutineContext().ensureActive()
                if (sibling.reference.uniqueKey == file.reference.uniqueKey) continue
                val name = MediaNameParser.parse(sibling.fileName)
                if (name.kind != "tv" || name.extra) continue
                // Only explicit shared ID or equal title/year evidence can reuse the work search.
                val sameTitle = MediaMatcher.normalize(name.title) == MediaMatcher.normalize(parsed.title) && name.year == parsed.year
                val sameId = name.explicitId == work.tmdbId && name.kind == work.mediaKind
                if ((!sameTitle && !sameId) || (name.explicitId != null && name.explicitId != work.tmdbId)) continue
                val other = repository.index(sibling, input.source)
                val previous = repository.dao.binding(other.assetId)
                count(
                    recognizer.recognize(other, force = force && previous?.locked != true && previous?.state != "IGNORED", sameWork = work)
                )
                repository.dao
                    .binding(other.assetId)
                    ?.failure
                    ?.let { run = run.copy(failure = it) }
            }
            run =
                run.copy(
                    completedDirectories = if (snapshot.complete) 1 else 0,
                    failure = if (snapshot.complete) run.failure else "DIRECTORY_INCOMPLETE"
                )
        }
        repository.requireSource(input.source)
        repository.dao.putScan(run.copy(state = if (run.failure == null) "COMPLETED" else "PARTIAL"))
    }
}
