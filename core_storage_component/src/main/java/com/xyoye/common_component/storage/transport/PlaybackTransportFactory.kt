package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

/** Assembly result, not a second cleanup owner. The file is borrowed from the lease. */
class AcquiredPlaybackTransport(
    val playbackFile: StorageFile,
    val lease: PlaybackTransportLease
)

sealed interface TransportAcquisitionResult {
    class Success(
        val transport: AcquiredPlaybackTransport
    ) : TransportAcquisitionResult

    data class Failure(
        val reason: TransportAcquisitionFailure,
        val cause: Exception? = null
    ) : TransportAcquisitionResult
}

enum class TransportAcquisitionFailure {
    ServerStartFailed,
    PortUnavailable,
    UpstreamUnavailable,
    ProtocolConnectionFailed,
    TaskCreationFailed,
    SessionUnavailable,
    Superseded,
    UnsupportedTransport,
    Unknown
}

internal class TransportAcquisitionException(
    val reason: TransportAcquisitionFailure
) : Exception(reason.name)

internal interface PlaybackTransportProvider {
    /**
     * Runs on the factory's I/O context. Until success, the factory owns isolated
     * SMB/FTP File/Storage and this provider owns only its new server/task/token.
     * On success the returned lease takes both. Other protocols borrow the file.
     * Clean newly created resources on failure, including undelivered results if
     * this implementation changes coroutine context during resource acquisition.
     */
    suspend fun acquirePlaybackTransport(
        file: StorageFile,
        profile: PlaybackProfile
    ): PlaybackTransportLease
}

class PlaybackTransportFactory internal constructor(
    private val createStorage: (MediaLibraryEntity) -> Storage?,
    private val providerFor: (Storage) -> PlaybackTransportProvider? = { it as? PlaybackTransportProvider },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor() : this(StorageFactory::createStorage, { it as? PlaybackTransportProvider }, Dispatchers.IO)

    suspend fun acquire(
        storage: Storage,
        file: StorageFile,
        profile: PlaybackProfile
    ): TransportAcquisitionResult {
        var ownedStorage: Storage? = null
        var ownedFile: StorageFile? = null
        var acquiredLease: PlaybackTransportLease? = null
        var delivered = false
        val acquisitionId = UUID.randomUUID().toString()

        try {
            if (file.storage !== storage) {
                return TransportAcquisitionResult.Failure(TransportAcquisitionFailure.UpstreamUnavailable)
            }
            // Capture browser metadata before dispatch; never transfer its connection.
            val library = storage.library.copy()
            val path = file.storagePath()
            val identity = file.uniqueKey()
            val history = file.playHistory?.copy()
            val isolate = library.mediaType == MediaType.SMB_SERVER || library.mediaType == MediaType.FTP_SERVER

            val result =
                withContext(ioDispatcher) {
                    val playbackStorage: Storage
                    val playbackFile: StorageFile
                    if (isolate) {
                        val created = createStorage(library)
                        if (created == null || created === storage) {
                            throw TransportAcquisitionException(TransportAcquisitionFailure.ProtocolConnectionFailed)
                        }
                        ownedStorage = created
                        if (created is AbstractStorage) {
                            created.throwPlaybackErrors = (storage as? AbstractStorage)?.throwPlaybackErrors == true
                        }
                        ensureActive()
                        val resolved = if (created is FtpStorage) created.resolvePlaybackFile(path) else created.pathFile(path, false)
                        if (resolved == null || resolved === file || resolved.storage !== created) {
                            throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
                        }
                        ownedFile = resolved
                        if (!resolved.isFile() || resolved.storagePath() != path || resolved.uniqueKey() != identity) {
                            throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
                        }
                        resolved.playHistory = history
                        playbackStorage = created
                        playbackFile = resolved
                    } else {
                        playbackStorage = storage
                        playbackFile = file
                    }
                    ensureActive()
                    val provider =
                        providerFor(playbackStorage)
                            ?: throw TransportAcquisitionException(TransportAcquisitionFailure.UnsupportedTransport)
                    val lease = provider.acquirePlaybackTransport(playbackFile, profile)
                    acquiredLease = lease
                    ensureActive()
                    TransportAcquisitionResult.Success(AcquiredPlaybackTransport(playbackFile, lease))
                }
            // Set only after withContext has delivered the result to the caller's context.
            delivered = true
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TransportAcquisitionException) {
            return TransportAcquisitionResult.Failure(failure.reason)
        } catch (failure: Exception) {
            return TransportAcquisitionResult.Failure(TransportAcquisitionFailure.Unknown, failure)
        } finally {
            if (!delivered) {
                withContext(NonCancellable + ioDispatcher) {
                    val lease = acquiredLease
                    val cleanup =
                        if (lease != null) {
                            listOf({ lease.release() })
                        } else {
                            listOf({
                                ownedFile?.close()
                                Unit
                            }, {
                                ownedStorage?.close()
                                Unit
                            })
                        }
                    releaseTransportResources(acquisitionId, cleanup)
                }
            }
        }
    }
}
