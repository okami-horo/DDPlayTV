package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.storage.file.impl.FtpStorageFile
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.utils.RangeUtils
import com.xyoye.common_component.utils.getFileExtension
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The Storage owns the FTP data stream; this server borrows it for one response. */
internal open class FtpPlayServer(
    private val storage: FtpStorage,
    private val file: FtpStorageFile,
    listenPort: Int = 0
) : NanoHTTPD("127.0.0.1", listenPort) {
    val leaseId: String = UUID.randomUUID().toString()
    private val startMutex = Mutex()
    private val requestLock = Any()
    private val retired = AtomicBoolean(false)

    override fun serve(session: IHTTPSession): Response =
        synchronized(requestLock) {
            if (retired.get() || !session.uri.startsWith("/lease/$leaseId/")) return notFound()
            val size = file.fileLength()
            val rangeText = session.headers["range"]
            val range = rangeText?.let { RangeUtils.parseRange(it, size) }
            if (rangeText != null && range == null) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").apply {
                    addHeader("Content-Range", "bytes */$size")
                }
            }
            val stream =
                storage.openFileBlocking(file, range?.first ?: 0)
                    ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "open resource failed")
            if (retired.get()) return notFound()
            newFixedLengthResponse(
                if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                "video/${getFileExtension(file.filePath())}",
                stream,
                range?.let { it.second - it.first + 1 } ?: size
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                range?.let { addHeader("Content-Range", "bytes ${it.first}-${it.second}/$size") }
            }
        }

    open suspend fun startSync(): Boolean =
        startMutex.withLock {
            synchronized(requestLock) {
                if (retired.get()) return@withLock false
                if (isAlive) return@withLock true
                try {
                    start()
                    isAlive
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }

    open fun playUrl(): String {
        check(isAlive && !retired.get()) { "FTP playback server is unavailable" }
        return "http://127.0.0.1:$listeningPort/lease/$leaseId/${URLEncoder.encode(file.fileName(), "UTF-8")}"
    }

    fun release() {
        if (!retired.compareAndSet(false, true)) return
        releaseTransportResources(
            leaseId,
            listOf({ stop() }, {
                // Wait for any opening transfer before the lease closes its Storage.
                synchronized(requestLock) { Unit }
            })
        )
    }

    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
}
