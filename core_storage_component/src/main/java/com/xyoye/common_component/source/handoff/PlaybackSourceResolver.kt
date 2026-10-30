package com.xyoye.common_component.source.handoff

import android.net.Uri
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackSourceView
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.credential.CredentialUnavailableException
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialResolver
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.RemoteStorageFile
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.storage.impl.RemoteStorage
import com.xyoye.common_component.storage.impl.ScreencastStorage
import com.xyoye.common_component.storage.transport.TransportAcquisitionException
import com.xyoye.common_component.storage.transport.TransportAcquisitionFailure
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PlaybackResolutionResult {
    class Success(
        val playback: ResolvedPlayback
    ) : PlaybackResolutionResult

    class Failure(
        val failure: PlaybackPreparationFailure
    ) : PlaybackResolutionResult
}

/** Source owns all resources. These descriptors and queue entries contain only recoverable inputs. */
class ResolvedPlayback(
    val source: StorageVideoSource,
    val descriptor: PlaybackDescriptor,
    val identity: PlaybackSourceView,
    queue: List<PlaybackDescriptor>,
    val downloadAdvice: DownloadValidator.ValidationOutcome.AllowPlayback? = null
) {
    val queue: List<PlaybackDescriptor> = Collections.unmodifiableList(queue.toList())
}

class PlaybackSourceResolver internal constructor(
    private val loadLibrary: suspend (Int) -> MediaLibraryEntity?,
    private val loadHistory: suspend (Int) -> PlayHistoryEntity?,
    private val findHistory: suspend (String, Int) -> PlayHistoryEntity?,
    private val backfillHistory: suspend (PlayHistoryEntity) -> Unit,
    private val createStorage: (MediaLibraryEntity) -> Storage?,
    private val resolveCredentials: (MediaLibraryEntity) -> MediaLibraryEntity,
    private val validateDownload: suspend (String, String, Long?) -> DownloadValidator.ValidationOutcome,
    private val createSource: suspend (StorageFile, PlaybackProfile?, () -> Unit) -> StorageVideoSource?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor() : this(
        MediaLibraryRepository::getById,
        { DatabaseProvider.instance.getPlayHistoryDao().getById(it) },
        { key, id -> DatabaseProvider.instance.getPlayHistoryDao().getPlayHistory(key, id) },
        { DatabaseProvider.instance.getPlayHistoryDao().backfillIdentity(it.id, it.uniqueKey, it.storagePath.orEmpty(), it.storageId!!) },
        StorageFactory::createStorage,
        { library ->
            if (library.mediaType in credentialMediaTypes) MediaLibraryCredentialResolver.resolveForPlayback(library) else library
        },
        { downloadId, mediaId, verified -> DownloadValidator().validate(downloadId, mediaId, verified) },
        { file, profile, cleanup ->
            StorageVideoSourceFactory.create(
                file,
                profile ?: StorageVideoSourceFactory.resolvePlaybackProfile(),
                listOf(file),
                cleanup
            )
        }
    )

    suspend fun resolve(
        descriptor: PlaybackDescriptor,
        profile: PlaybackProfile? = null
    ): PlaybackResolutionResult {
        val ownership = ResolutionOwnership()
        var source: StorageVideoSource? = null
        var delivered = false
        try {
            val result =
                withContext(ioDispatcher) {
                    val resolved = resolveFile(descriptor, ownership)
                    val file = resolved.file
                    if (!file.isFile()) fail(PlaybackPreparationFailure.FileMissing)
                    if (!file.canRead()) fail(PlaybackPreparationFailure.PermissionDenied)
                    if (file.storage.library.mediaType == MediaType.LOCAL_STORAGE) {
                        val local = java.io.File(file.filePath())
                        if (!local.isFile) fail(PlaybackPreparationFailure.FileMissing)
                        if (!local.canRead()) fail(PlaybackPreparationFailure.PermissionDenied)
                    }
                    ensureActive()
                    val created =
                        createSource(file, profile, ownership::close)
                            ?: fail(PlaybackPreparationFailure.FileMissing)
                    source = created
                    ensureActive()
                    // SMB/FTP factories acquire a second connection. Retire this metadata query now.
                    if (file.storage.library.mediaType in isolatedMediaTypes) ownership.close()
                    val identity =
                        PlaybackSourceView(
                            file.uniqueKey(),
                            file.uniqueKey(),
                            file.storage.library.mediaType,
                            (descriptor as? ExternalUriDescriptor)?.displayTitle ?: file.fileName(),
                            file.storage.library.id
                        )
                    PlaybackResolutionResult.Success(
                        ResolvedPlayback(created, resolved.descriptor, identity, resolved.queue, resolved.advice)
                    )
                }
            delivered = true
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throwWrappedCancellation(failure)
            return PlaybackResolutionResult.Failure(classify(failure))
        } finally {
            if (!delivered) {
                withContext(NonCancellable + ioDispatcher) {
                    source?.releasePlaybackResources()
                    ownership.close()
                }
            }
        }
    }

    private suspend fun resolveFile(
        descriptor: PlaybackDescriptor,
        owner: ResolutionOwnership
    ): FileResolution =
        when (descriptor) {
            is StoredLibraryFileDescriptor -> resolveLibrary(descriptor, owner)
            is PlayHistoryDescriptor -> resolveHistory(descriptor, owner)
            is TorrentDescriptor -> resolveTorrent(descriptor, owner)
            is ExternalUriDescriptor -> {
                if (descriptor.uri.isBlank()) fail(PlaybackPreparationFailure.InvalidDescriptor)
                val storage =
                    owner.storage(MediaLibraryEntity.STREAM.copy(url = descriptor.uri)) as? LinkStorage
                        ?: fail(PlaybackPreparationFailure.UnsupportedSource)
                storage.setupHttpHeader(descriptor.headers.toMap())
                FileResolution(owner.file(storage.getRootFile()), descriptor, listOf(descriptor))
            }
            is ScreencastDescriptor -> {
                if (descriptor.senderAddress.isBlank() ||
                    descriptor.port !in 1..65535 ||
                    descriptor.relatedVideos.none { it.uniqueKey == descriptor.playUniqueKey }
                ) {
                    fail(PlaybackPreparationFailure.InvalidDescriptor)
                }
                val library =
                    MediaLibraryEntity(
                        displayName = "投屏",
                        url = "",
                        mediaType = MediaType.SCREEN_CAST,
                        screencastAddress = descriptor.senderAddress,
                        port = descriptor.port
                    )
                val storage = owner.storage(library) as? ScreencastStorage ?: fail(PlaybackPreparationFailure.UnsupportedSource)
                val data =
                    ScreencastData(
                        descriptor.port,
                        descriptor.relatedVideos.map {
                            ScreencastVideoData(
                                it.title,
                                it.uniqueKey,
                                it.episodeId,
                                it.danmuFileName,
                                it.subtitleFileName,
                                it.positionMs,
                                it.durationMs
                            )
                        },
                        descriptor.playUniqueKey,
                        descriptor.httpHeaders.toMap()
                    ).apply { ip = descriptor.senderAddress }
                storage.bindScreencastData(data)
                val file = storage.getRootFile() ?: fail(PlaybackPreparationFailure.FileMissing)
                FileResolution(owner.file(file), descriptor, descriptor.relatedVideos.map { descriptor.copy(playUniqueKey = it.uniqueKey) })
            }
        }

    private suspend fun resolveLibrary(
        descriptor: StoredLibraryFileDescriptor,
        owner: ResolutionOwnership
    ): FileResolution {
        val library = loadLibrary(descriptor.libraryId)?.copy() ?: fail(PlaybackPreparationFailure.LibraryMissing)
        if (library.id != descriptor.libraryId || library.mediaType != descriptor.mediaType) {
            fail(PlaybackPreparationFailure.FileMissing)
        }
        val storage = owner.storage(library)
        val context = descriptor.queueContext
        val directory =
            context?.directory?.let { reference ->
                storage.pathFile(reference.storagePath, true)?.let(owner::file)?.takeIf { it.uniqueKey() == reference.uniqueKey }
            }
        val listed = directory?.let { storage.openDirectory(it, false).also(owner::files) }
        val listedFile = listed?.firstOrNull { it.uniqueKey() == descriptor.file.uniqueKey }
        val file =
            listedFile ?: resolveReference(storage, descriptor.file)?.let(owner::file)
                ?: fail(PlaybackPreparationFailure.FileMissing)
        requireIdentity(file, descriptor.file)
        val savedHistory =
            findHistory(file.uniqueKey(), library.id)?.copy()
                ?: if (file is RemoteStorageFile && file.uniqueKey() != descriptor.file.uniqueKey) {
                    (
                        descriptor.file.playHistoryId?.let { loadHistory(it) }
                            ?: findHistory(descriptor.file.uniqueKey, library.id)
                    )?.takeIf {
                        it.storageId == library.id &&
                            it.mediaType == library.mediaType &&
                            it.uniqueKey == descriptor.file.uniqueKey &&
                            remoteHistoryVideoId(it) == file.getRealFile().Id
                    }
                } else {
                    null
                }
        if (file is RemoteStorageFile && savedHistory != null && file.uniqueKey() != descriptor.file.uniqueKey) {
            restoreHistory(file, savedHistory, library.id)
        } else {
            file.playHistory = savedHistory
        }
        val references =
            if (context != null && listed != null) {
                PlaybackReferenceFactory
                    .orderedVideos(listed, context.sort, context.hideHiddenFiles, context.sortAscending)
                    .map(PlaybackReferenceFactory::reference)
            } else {
                context?.fallbackItems.orEmpty()
            }
        val selected = PlaybackReferenceFactory.reference(file)
        val queue =
            references
                .map {
                    if (file is RemoteStorageFile &&
                        !selected.protocolReference.isNullOrBlank() &&
                        it.protocolReference == selected.protocolReference
                    ) {
                        selected
                    } else {
                        it
                    }
                }.toMutableList()
                .apply {
                    if (none { it.uniqueKey == selected.uniqueKey }) add(selected)
                }.map { descriptor.copy(file = it, queueContext = null) }
        return FileResolution(file, descriptor.copy(file = selected), queue)
    }

    private suspend fun resolveReference(
        storage: Storage,
        reference: PlaybackFileReference
    ): StorageFile? =
        when {
            storage is RemoteStorage -> storage.resolvePlaybackReference(reference.storagePath, reference.protocolReference, false)
            else -> storage.pathFile(reference.storagePath, false)
        }

    private suspend fun resolveHistory(
        descriptor: PlayHistoryDescriptor,
        owner: ResolutionOwnership
    ): FileResolution {
        val history = loadHistory(descriptor.historyId)?.copy() ?: fail(PlaybackPreparationFailure.FileMissing)
        if (history.uniqueKey != descriptor.expectedUniqueKey ||
            (descriptor.expectedLibraryId != null && history.storageId != descriptor.expectedLibraryId)
        ) {
            fail(PlaybackPreparationFailure.FileMissing)
        }
        val libraryId =
            history.storageId ?: when (history.mediaType) {
                MediaType.LOCAL_STORAGE -> MediaLibraryEntity.LOCAL.id
                MediaType.STREAM_LINK -> MediaLibraryEntity.STREAM.id
                MediaType.MAGNET_LINK -> MediaLibraryEntity.TORRENT.id
                else -> fail(PlaybackPreparationFailure.LibraryMissing)
            }
        val library = loadLibrary(libraryId)?.copy() ?: fail(PlaybackPreparationFailure.LibraryMissing)
        if (library.id != libraryId || library.mediaType != history.mediaType) fail(PlaybackPreparationFailure.FileMissing)
        if (history.mediaType == MediaType.MAGNET_LINK) {
            val path = history.torrentPath ?: fail(PlaybackPreparationFailure.FileMissing)
            val torrent =
                TorrentDescriptor(
                    path,
                    history.torrentIndex,
                    com.xyoye.common_component.utils.thunder.ThunderManager
                        .media3DownloadId(path, history.torrentIndex),
                    history.uniqueKey
                )
            val resolved = resolveTorrent(torrent, owner)
            restoreHistory(resolved.file, history, libraryId)
            return resolved
        }
        val storage = owner.storage(library)
        if (storage is LinkStorage) storage.setupHttpHeader(history.httpHeader?.let(JsonHelper::parseJsonMap))
        val recoveredHistory = history.copy(storageId = libraryId, storagePath = history.storagePath ?: legacyPath(history))
        val file = storage.historyFile(recoveredHistory)?.let(owner::file) ?: fail(PlaybackPreparationFailure.FileMissing)
        if (history.uniqueKey.isNotBlank() || file is RemoteStorageFile) {
            requireIdentity(
                file,
                PlaybackFileReference(
                    history.uniqueKey,
                    history.storagePath.orEmpty(),
                    protocolReference = remoteHistoryVideoId(history)
                )
            )
        }
        restoreHistory(file, history, libraryId)
        val normalized = descriptor.copy(expectedUniqueKey = file.uniqueKey(), expectedLibraryId = libraryId)
        return FileResolution(file, normalized, listOf(normalized))
    }

    private suspend fun restoreHistory(
        file: StorageFile,
        history: PlayHistoryEntity,
        libraryId: Int
    ) {
        val restored = history.copy(uniqueKey = file.uniqueKey(), storagePath = file.storagePath(), storageId = libraryId)
        file.playHistory = restored
        if (history.uniqueKey != restored.uniqueKey || history.storagePath != restored.storagePath || history.storageId != libraryId) {
            backfillHistory(restored)
        }
    }

    private suspend fun resolveTorrent(
        descriptor: TorrentDescriptor,
        owner: ResolutionOwnership
    ): FileResolution {
        if (descriptor.fileIndex < 0 || descriptor.magnetOrTorrentIdentity.isBlank()) fail(PlaybackPreparationFailure.InvalidDescriptor)
        val advice =
            when (val outcome = validateDownload(descriptor.downloadId, descriptor.downloadId, null)) {
                is DownloadValidator.ValidationOutcome.Blocked ->
                    fail(
                        PlaybackPreparationFailure.OfflineValidationBlocked(outcome.requiredAction)
                    )
                is DownloadValidator.ValidationOutcome.AllowPlayback -> outcome
            }
        val storage = owner.storage(MediaLibraryEntity.TORRENT.copy(url = descriptor.magnetOrTorrentIdentity))
        val root = storage.getRootFile()?.let(owner::file) ?: fail(PlaybackPreparationFailure.FileMissing)
        val items = storage.openDirectory(root, false).also(owner::files)
        val file =
            items.filterIsInstance<TorrentStorageFile>().firstOrNull { it.getFileInfo().fileIndex == descriptor.fileIndex }
                ?: fail(PlaybackPreparationFailure.FileMissing)
        if (descriptor.expectedUniqueKey.isNotBlank()) requireIdentity(file, descriptor.expectedUniqueKey)
        file.playHistory = findHistory(file.uniqueKey(), storage.library.id)?.copy()
        val queue =
            items
                .filter { it.isVideoFile() }
                .map {
                    PlaybackReferenceFactory.fromFile(it, com.xyoye.data_component.enums.StorageSort.NAME, false, true)
                }.toMutableList()
        val normalized = descriptor.copy(expectedUniqueKey = file.uniqueKey(), displayTitle = file.fileName())
        if (queue.none { (it as? TorrentDescriptor)?.expectedUniqueKey == file.uniqueKey() }) queue.add(normalized)
        return FileResolution(file, normalized, queue, advice)
    }

    private fun requireIdentity(
        file: StorageFile,
        reference: PlaybackFileReference
    ) {
        if (file is RemoteStorageFile && file.storage.library.mediaType == MediaType.REMOTE_STORAGE) {
            val data = file.getRealFile()
            val videoId = reference.protocolReference
            if (!videoId.isNullOrBlank() && data.Id != videoId) fail(PlaybackPreparationFailure.FileMissing)
            if (file.uniqueKey() == reference.uniqueKey) return
            // Old grouped PC entries had an empty path. A saved video ID is required to disambiguate them.
            if (!videoId.isNullOrBlank() &&
                data.Hash.isEmpty() &&
                (reference.uniqueKey == reference.storagePath.toMd5String() || reference.uniqueKey == "".toMd5String())
            ) {
                return
            }
        }
        requireIdentity(file, reference.uniqueKey)
    }

    private fun requireIdentity(
        file: StorageFile,
        expected: String
    ) {
        if (file.uniqueKey() != expected) fail(PlaybackPreparationFailure.FileMissing)
    }

    private inner class ResolutionOwnership {
        private val closed = AtomicBoolean(false)
        private var storage: Storage? = null
        private val files = Collections.newSetFromMap(java.util.IdentityHashMap<StorageFile, Boolean>())

        fun storage(library: MediaLibraryEntity): Storage {
            val credentials =
                try {
                    resolveCredentials(library)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail(PlaybackPreparationFailure.CredentialUnavailable)
                }
            return (createStorage(credentials) ?: fail(PlaybackPreparationFailure.UnsupportedSource)).also {
                storage = it
                (it as? AbstractStorage)?.throwPlaybackErrors = true
            }
        }

        fun file(file: StorageFile): StorageFile = file.also { files.add(it) }

        fun files(values: List<StorageFile>) {
            files.addAll(values)
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseTransportResources(
                    "resolution",
                    files.map { file -> { file.close() } } +
                        listOf({
                            storage?.close()
                            Unit
                        })
                )
            }
        }
    }

    private class FileResolution(
        val file: StorageFile,
        val descriptor: PlaybackDescriptor,
        val queue: List<PlaybackDescriptor>,
        val advice: DownloadValidator.ValidationOutcome.AllowPlayback? = null
    )

    private class ResolutionFailure(
        val failure: PlaybackPreparationFailure
    ) : Exception()

    private companion object {
        val isolatedMediaTypes = setOf(MediaType.SMB_SERVER, MediaType.FTP_SERVER)
        val credentialMediaTypes =
            isolatedMediaTypes +
                setOf(
                    MediaType.WEBDAV_SERVER,
                    MediaType.REMOTE_STORAGE,
                    MediaType.SCREEN_CAST,
                    MediaType.ALSIT_STORAGE
                )

        fun fail(failure: PlaybackPreparationFailure): Nothing = throw ResolutionFailure(failure)

        fun legacyPath(history: PlayHistoryEntity): String =
            when (history.mediaType) {
                MediaType.EXTERNAL_STORAGE -> history.url
                else -> Uri.parse(history.url).path ?: history.url
            }

        fun remoteHistoryVideoId(history: PlayHistoryEntity): String? {
            if (history.mediaType != MediaType.REMOTE_STORAGE) return null
            val parts = Uri.parse(history.url).pathSegments
            return parts
                .takeIf { it.size == 5 && it.take(4) == listOf("api", "v1", "stream", "id") }
                ?.last()
                ?.takeIf { it.isNotBlank() }
        }

        fun throwWrappedCancellation(failure: Throwable) {
            var current: Throwable? = failure
            val seen = Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (current != null && seen.add(current)) {
                if (current is CancellationException) throw current
                current = current.cause
            }
        }

        fun classify(failure: Exception): PlaybackPreparationFailure =
            when (failure) {
                is ResolutionFailure -> failure.failure
                is TransportAcquisitionException ->
                    when (failure.reason) {
                        TransportAcquisitionFailure.UpstreamUnavailable -> PlaybackPreparationFailure.FileMissing
                        TransportAcquisitionFailure.UnsupportedTransport -> PlaybackPreparationFailure.UnsupportedSource
                        TransportAcquisitionFailure.Superseded -> PlaybackPreparationFailure.RequestSuperseded
                        TransportAcquisitionFailure.ProtocolConnectionFailed -> PlaybackPreparationFailure.NetworkUnavailable
                        else -> PlaybackPreparationFailure.Unknown(failure.reason.name)
                    }
                is CredentialUnavailableException -> PlaybackPreparationFailure.CredentialUnavailable
                is javax.security.auth.login.LoginException -> PlaybackPreparationFailure.AuthenticationRequired
                is com.hierynomus.mssmb2.SMBApiException ->
                    when (failure.statusCode) {
                        0xC000006DL, 0xC000006AL, 0xC0000064L -> PlaybackPreparationFailure.AuthenticationRequired
                        0xC0000022L -> PlaybackPreparationFailure.PermissionDenied
                        0xC0000034L, 0xC000003AL -> PlaybackPreparationFailure.FileMissing
                        else -> PlaybackPreparationFailure.NetworkUnavailable
                    }
                is com.xyoye.sardine.impl.SardineException ->
                    when (failure.statusCode) {
                        401 -> PlaybackPreparationFailure.AuthenticationRequired
                        403 -> PlaybackPreparationFailure.PermissionDenied
                        404 -> PlaybackPreparationFailure.FileMissing
                        else -> PlaybackPreparationFailure.NetworkUnavailable
                    }
                is SecurityException -> PlaybackPreparationFailure.PermissionDenied
                is FileNotFoundException -> PlaybackPreparationFailure.FileMissing
                is NetworkException ->
                    when (failure.code) {
                        401, -101 -> PlaybackPreparationFailure.AuthenticationRequired
                        403 -> PlaybackPreparationFailure.PermissionDenied
                        404 -> PlaybackPreparationFailure.FileMissing
                        else -> PlaybackPreparationFailure.NetworkUnavailable
                    }
                is IOException -> PlaybackPreparationFailure.NetworkUnavailable
                else -> PlaybackPreparationFailure.Unknown(failure.javaClass.simpleName)
            }
    }
}
