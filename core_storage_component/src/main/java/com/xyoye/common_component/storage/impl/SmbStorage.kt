package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.SmbPlayServer
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportProvider
import com.xyoye.common_component.storage.transport.SmbPlaybackTransport
import com.xyoye.common_component.storage.transport.TransportAcquisitionException
import com.xyoye.common_component.storage.transport.TransportAcquisitionFailure
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

class SmbStorage internal constructor(
    library: MediaLibraryEntity,
    private val connection: SmbConnection,
    private val createServer: (SmbStorage, SmbStorageFile) -> SmbPlayServer = { storage, file -> SmbPlayServer(storage, file) }
) : AbstractStorage(library),
    PlaybackTransportProvider {
    constructor(library: MediaLibraryEntity) : this(library, SmbClientConnection(library))

    private val connectionLock = Any()
    private var closed = false

    override var rootUri: Uri = Uri.parse("smb://${library.url}")

    override suspend fun listFiles(file: StorageFile): List<StorageFile> =
        access(emptyList()) {
            if (file.storage !== this) return@access emptyList()
            if (file.isRootFile()) return@access connection.shares().map { SmbStorageFile(this, it, "") }
            val smbFile = file as? SmbStorageFile ?: return@access emptyList()
            val share = smbFile.getShareName() ?: return@access emptyList()
            connection.selectShare(share)
            connection.list(file.filePath()).mapNotNull { name ->
                val path = listOf(file.filePath(), name).filter { it.isNotEmpty() }.joinToString("/")
                access<StorageFile?>(null) { resolvedFile(share, path) }
            }
        }

    override suspend fun getRootFile(): StorageFile? {
        val share = library.smbSharePath?.let { Uri.parse(it).pathSegments.firstOrNull() }
        if (share == null) return access(null) { SmbStorageFile(this, null, "") }
        return access(null) {
            connection.selectShare(share)
            SmbStorageFile(this, share, "")
        }
    }

    override suspend fun openFile(file: StorageFile): InputStream? = (file as? SmbStorageFile)?.let(::openFileBlocking)

    internal fun openFileBlocking(file: SmbStorageFile): InputStream? =
        access(null) {
            if (file.storage !== this) return@access null
            connection.selectShare(file.getShareName() ?: return@access null)
            connection.open(file.filePath())
        }

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? =
        access(null) {
            val segments = Uri.parse(path).pathSegments
            val share = segments.firstOrNull() ?: return@access null
            connection.selectShare(share)
            resolvedFile(share, segments.drop(1).joinToString("/"))
        }

    private fun resolvedFile(
        share: String,
        path: String
    ): SmbStorageFile {
        val info = connection.stat(path)
        return SmbStorageFile(this, share, path, info.length, info.isDirectory)
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? =
        history.storagePath?.let { pathFile(it, false) }?.also { it.playHistory = history }

    override suspend fun acquirePlaybackTransport(
        file: StorageFile,
        profile: PlaybackProfile
    ): PlaybackTransportLease {
        val smbFile = file as? SmbStorageFile
        if (smbFile == null || file.storage !== this || !file.isFile()) {
            throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
        }
        val server = createServer(this, smbFile)
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            if (!server.startSync()) throw TransportAcquisitionException(TransportAcquisitionFailure.ServerStartFailed)
            currentCoroutineContext().ensureActive()
            val lease = SmbPlaybackTransport(server, smbFile, this)
            transferred = true
            return lease
        } finally {
            // Before transfer, the factory still owns File/Storage.
            if (!transferred) server.release()
        }
    }

    override suspend fun test(): Boolean = getRootFile()?.let { listFiles(it).isNotEmpty() } ?: false

    override fun close() {
        synchronized(connectionLock) {
            if (closed) return
            closed = true
        }
        releaseTransportResources("smb-storage", listOf({ connection.close() }))
    }

    private inline fun <T> access(
        fallback: T,
        operation: () -> T
    ): T =
        synchronized(connectionLock) {
            if (closed) return fallback
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (throwPlaybackErrors) throw failure
                LogFacade.w(LogModule.STORAGE, "SmbStorage", "SMB operation failed")
                fallback
            }
        }
}
