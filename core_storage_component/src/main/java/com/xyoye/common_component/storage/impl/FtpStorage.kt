package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.FtpPlayServer
import com.xyoye.common_component.storage.file.impl.FtpStorageFile
import com.xyoye.common_component.storage.transport.FtpPlaybackTransport
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportProvider
import com.xyoye.common_component.storage.transport.TransportAcquisitionException
import com.xyoye.common_component.storage.transport.TransportAcquisitionFailure
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.FilterInputStream
import java.io.InputStream

class FtpStorage internal constructor(
    library: MediaLibraryEntity,
    private val client: FTPClient,
    private val createServer: (FtpStorage, FtpStorageFile) -> FtpPlayServer = { storage, file -> FtpPlayServer(storage, file) }
) : AbstractStorage(library),
    PlaybackTransportProvider {
    constructor(library: MediaLibraryEntity) : this(library, FTPClient())

    private val connectionLock = Any()
    private var closed = false
    private var connectionMayBeOpen = false
    private var pendingRead: PendingRead? = null

    override suspend fun listFiles(file: StorageFile): List<StorageFile> =
        access(emptyList()) {
            if (file.storage !== this) return@access emptyList()
            if (client.printWorkingDirectory() != "/" && !client.changeWorkingDirectory("/")) {
                return@access emptyList()
            }
            client.listFiles(file.filePath()).map { FtpStorageFile(this, file.filePath(), it) }
        }

    override suspend fun getRootFile(): StorageFile =
        FtpStorageFile(
            this,
            "/",
            FTPFile().apply {
                name = ""
                type = FTPFile.DIRECTORY_TYPE
            }
        )

    override suspend fun openFile(file: StorageFile): InputStream? = (file as? FtpStorageFile)?.let { openFileBlocking(it) }

    suspend fun openFile(
        file: StorageFile,
        offset: Long
    ): InputStream? = (file as? FtpStorageFile)?.let { openFileBlocking(it, offset) }

    internal fun openFileBlocking(
        file: FtpStorageFile,
        offset: Long = 0
    ): InputStream? =
        access(null) {
            if (file.storage !== this) return@access null
            client.restartOffset = offset.coerceAtLeast(0)
            val stream = client.retrieveFileStream(file.filePath()) ?: return@access null
            PendingRead(stream).also { pendingRead = it }
        }

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? =
        access(null) {
            val metadata =
                lookupMetadata(path) ?: FTPFile().apply {
                    name = Uri.parse(path).lastPathSegment.orEmpty()
                    type = if (isDirectory) FTPFile.DIRECTORY_TYPE else FTPFile.FILE_TYPE
                }
            resolvedFile(path, metadata)
        }

    /** Playback never accepts the browser's synthetic fallback metadata. */
    internal fun resolvePlaybackFile(path: String): StorageFile? =
        access(null) {
            val metadata = lookupMetadata(path)?.takeIf { it.isFile } ?: return@access null
            resolvedFile(path, metadata)
        }

    private fun lookupMetadata(path: String): FTPFile? {
        val name = Uri.parse(path).lastPathSegment ?: return null
        val metadata =
            try {
                client.mlistFile(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (metadata != null) return metadata
        // Some servers do not support MLST; a matching LIST entry is still real metadata.
        return try {
            client.listFiles(parentPath(path)).firstOrNull { it.name == name }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (throwPlaybackErrors) throw failure
            null
        }
    }

    private fun resolvedFile(
        path: String,
        metadata: FTPFile
    ): FtpStorageFile {
        // MLST can return the full path as its name; bind to the requested identity.
        metadata.name = Uri.parse(path).lastPathSegment.orEmpty()
        return FtpStorageFile(this, parentPath(path), metadata)
    }

    private fun parentPath(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? =
        history.storagePath?.let { pathFile(it, false) }?.also { it.playHistory = history }

    override suspend fun acquirePlaybackTransport(
        file: StorageFile,
        profile: PlaybackProfile
    ): PlaybackTransportLease {
        val ftpFile = file as? FtpStorageFile
        if (ftpFile == null || file.storage !== this || !file.isFile()) {
            throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
        }
        val server = createServer(this, ftpFile)
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            if (!server.startSync()) throw TransportAcquisitionException(TransportAcquisitionFailure.ServerStartFailed)
            currentCoroutineContext().ensureActive()
            val lease = FtpPlaybackTransport(server, ftpFile, this)
            transferred = true
            return lease
        } finally {
            if (!transferred) server.release()
        }
    }

    override suspend fun test(): Boolean = access(false) { true }

    private fun checkConnection(): Boolean {
        if (client.isAvailable) {
            connectionMayBeOpen = true
            return true
        }
        connectionMayBeOpen = true
        client.controlEncoding = library.ftpEncoding
        client.connect(library.ftpAddress, library.port)
        val loggedIn =
            if (library.isAnonymous) {
                client.login("anonymous", "anonymous")
            } else {
                client.login(library.account, library.password)
            }
        if (!loggedIn) {
            disconnectConnection()
            if (throwPlaybackErrors) throw javax.security.auth.login.LoginException("FTP authentication failed")
            return false
        }
        if (library.isActiveFTP) client.enterLocalActiveMode() else client.enterLocalPassiveMode()
        if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
            disconnectConnection()
            return false
        }
        client.listHiddenFiles = true
        return true
    }

    fun completePending() = synchronized(connectionLock) { finishPending() }

    private inner class PendingRead(
        val raw: InputStream
    ) : FilterInputStream(raw) {
        override fun close() =
            synchronized(connectionLock) {
                // A late response close must never finish a newer transfer.
                if (pendingRead === this) finishPending()
            }
    }

    private fun finishPending() {
        val previous = pendingRead ?: return
        pendingRead = null
        var failed = false
        try {
            previous.raw.close()
        } catch (_: Exception) {
            failed = true
        }
        try {
            if (!client.completePendingCommand()) failed = true
        } catch (_: Exception) {
            failed = true
        }
        if (failed) disconnectConnection()
    }

    private fun disconnectConnection() {
        if (!connectionMayBeOpen) return
        connectionMayBeOpen = false
        releaseTransportResources("ftp-connection", listOf({ client.disconnect() }))
    }

    override fun close() {
        synchronized(connectionLock) {
            if (closed) return
            closed = true
        }
        releaseTransportResources(
            "ftp-storage",
            listOf({
                synchronized(connectionLock) { finishPending() }
            }, {
                synchronized(connectionLock) { disconnectConnection() }
            })
        )
    }

    private inline fun <T> access(
        fallback: T,
        operation: () -> T
    ): T =
        synchronized(connectionLock) {
            if (closed) return fallback
            try {
                finishPending()
                if (!checkConnection()) return fallback
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                disconnectConnection()
                if (throwPlaybackErrors) throw failure
                LogFacade.w(LogModule.STORAGE, "FtpStorage", "FTP operation failed")
                fallback
            }
        }
}
