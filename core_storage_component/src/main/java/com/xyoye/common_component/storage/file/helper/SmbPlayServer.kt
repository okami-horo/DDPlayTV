package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.utils.RangeUtils
import com.xyoye.common_component.utils.getFileExtension
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One server per playback connection. Its streams never belong to another source. */
internal open class SmbPlayServer(
    private val storage: SmbStorage,
    private val file: SmbStorageFile,
    listenPort: Int = 0
) : NanoHTTPD("127.0.0.1", listenPort) {
    val leaseId: String = UUID.randomUUID().toString()
    private val startMutex = Mutex()
    private val requestLock = Any()
    private val streamsLock = Any()
    private val streams = mutableSetOf<InputStream>()
    private val retired = AtomicBoolean(false)

    override fun serve(session: IHTTPSession): Response =
        synchronized(requestLock) {
            if (retired.get() || !session.uri.startsWith("/lease/$leaseId/")) {
                return notFound()
            }
            val rangeText = session.headers["range"]
            val range = rangeText?.let { RangeUtils.parseRange(it, file.fileLength()) }
            if (rangeText != null && range == null) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            }
            val input =
                storage.openFileBlocking(file)
                    ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "open resource failed")
            val owned = own(input)
            try {
                var remaining = range?.first ?: 0
                while (remaining > 0) {
                    val skipped = owned.skip(remaining)
                    if (skipped > 0) {
                        remaining -= skipped
                    } else {
                        if (owned.read() == -1) throw IOException("SMB range offset unavailable")
                        remaining--
                    }
                }
                if (retired.get()) {
                    owned.close()
                    return notFound()
                }
                val length = range?.let { it.second - it.first + 1 } ?: file.fileLength()
                newFixedLengthResponse(
                    if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    "video/${getFileExtension(file.filePath())}",
                    owned,
                    length
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    range?.let { addHeader("Content-Range", "bytes ${it.first}-${it.second}/${file.fileLength()}") }
                }
            } catch (_: Exception) {
                owned.close()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "read resource failed")
            }
        }

    private fun own(input: InputStream): InputStream {
        val owned =
            object : FilterInputStream(input) {
                private val closed = AtomicBoolean(false)

                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    synchronized(streamsLock) { streams.remove(this) }
                    releaseTransportResources(leaseId, listOf({ input.close() }))
                }
            }
        synchronized(streamsLock) { streams.add(owned) }
        return owned
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
        check(isAlive && !retired.get()) { "SMB playback server is unavailable" }
        return "http://127.0.0.1:$listeningPort/lease/$leaseId/${URLEncoder.encode(file.fileName(), "UTF-8")}"
    }

    fun release() {
        if (!retired.compareAndSet(false, true)) return
        releaseTransportResources(
            leaseId,
            listOf({ stop() }, {
                val remaining =
                    synchronized(requestLock) {
                        synchronized(streamsLock) { streams.toList().also { streams.clear() } }
                    }
                releaseTransportResources(leaseId, remaining.map { stream -> { stream.close() } })
            })
        )
    }

    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
}
