package com.xyoye.common_component.storage.impl

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.xyoye.common_component.extension.open
import com.xyoye.common_component.extension.openDirectory
import com.xyoye.common_component.extension.openFile
import com.xyoye.common_component.extension.standardFileInfo
import com.xyoye.common_component.storage.StorageProtocolDefaults
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.data_component.entity.MediaLibraryEntity
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Protocol seam shared by browsing and playback; each Storage owns one connection. */
internal interface SmbConnection : Closeable {
    fun shares(): List<String>

    fun selectShare(name: String)

    fun stat(path: String): SmbFileInfo

    fun list(path: String): List<String>

    fun open(path: String): InputStream
}

internal data class SmbFileInfo(
    val length: Long,
    val isDirectory: Boolean
)

internal class SmbClientConnection(
    private val library: MediaLibraryEntity
) : SmbConnection {
    private val client = SMBClient()
    private var session: Session? = null
    private var share: DiskShare? = null
    private val closed = AtomicBoolean(false)

    private fun connectedSession(): Session {
        check(!closed.get()) { "SMB connection is closed" }
        session?.takeIf { it.connection.isConnected }?.let { return it }
        closeShare()
        val authentication =
            if (library.isAnonymous) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(library.account, library.password.orEmpty().toCharArray(), null)
            }
        val port = library.port.takeIf { it != 0 } ?: StorageProtocolDefaults.SMB_DEFAULT_PORT
        return client.connect(library.url, port).authenticate(authentication).also { session = it }
    }

    override fun shares(): List<String> =
        ServerService(SMBTransportFactories.SRVSVC.getTransport(connectedSession())).shares0.map { it.netName }

    override fun selectShare(name: String) {
        val connected = connectedSession()
        if (share?.smbPath?.shareName == name && share?.isConnected == true) return
        closeShare()
        share = connected.connectShare(name) as DiskShare
        check(share?.isConnected == true) { "SMB share is unavailable" }
    }

    override fun stat(path: String): SmbFileInfo =
        currentShare().open(path).use { entry ->
            val info = entry.standardFileInfo()
            SmbFileInfo(if (info.isDirectory) 0 else info.endOfFile, info.isDirectory)
        }

    override fun list(path: String): List<String> =
        currentShare().openDirectory(path).use { directory ->
            directory.list().map { it.fileName }.filter { it != "." && it != ".." }
        }

    override fun open(path: String): InputStream {
        val file = currentShare().openFile(path)
        try {
            return object : FilterInputStream(file.inputStream) {
                private val streamClosed = AtomicBoolean(false)

                override fun close() {
                    if (!streamClosed.compareAndSet(false, true)) return
                    // SMBJ 0.10's FileInputStream.close does not close its remote File handle.
                    try {
                        super.close()
                    } finally {
                        file.close()
                    }
                }
            }
        } catch (failure: Exception) {
            releaseTransportResources("smb-open", listOf({ file.close() }))
            throw failure
        }
    }

    private fun currentShare(): DiskShare {
        check(!closed.get()) { "SMB connection is closed" }
        return checkNotNull(share) { "SMB share is unavailable" }
    }

    private fun closeShare() {
        val previous = share
        share = null
        previous?.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session = null
        releaseTransportResources("smb-connection", listOf(::closeShare, { client.close() }))
    }
}
