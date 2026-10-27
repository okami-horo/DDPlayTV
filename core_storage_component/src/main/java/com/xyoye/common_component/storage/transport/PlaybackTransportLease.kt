package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

interface PlaybackTransportLease {
    val leaseId: String
    val playUrl: String
    val transportType: PlaybackTransportType

    /** True once retirement starts; cleanup failures are reported separately. */
    val isReleased: Boolean

    /** Retire only after the backend has stopped using this source. May block. */
    fun release()
}

enum class PlaybackTransportType {
    DIRECT,
    HTTP_PROXY,
    SMB,
    FTP,
    THUNDER,
    BILIBILI
}

internal class ManagedPlaybackTransportLease(
    override val playUrl: String,
    override val transportType: PlaybackTransportType,
    cleanup: List<() -> Unit>,
    override val leaseId: String = UUID.randomUUID().toString()
) : PlaybackTransportLease {
    private val retired = AtomicBoolean(false)
    private val cleanup = cleanup.toList()

    init {
        require(playUrl.isNotBlank()) { "A transport lease requires a playback URL" }
    }

    override val isReleased: Boolean
        get() = retired.get()

    override fun release() {
        if (retired.compareAndSet(false, true)) {
            releaseTransportResources(leaseId, cleanup)
        }
    }
}

internal fun releaseTransportResources(
    leaseId: String,
    cleanup: List<() -> Unit>
) {
    cleanup.forEachIndexed { index, close ->
        try {
            close()
        } catch (_: Exception) {
            // Protocol exception messages can contain credentials, URLs or local paths.
            runCatching {
                LogFacade.w(
                    LogModule.STORAGE,
                    "PlaybackTransportLease",
                    "Transport cleanup failed",
                    mapOf("leaseId" to leaseId, "cleanupStep" to index.toString())
                )
            }
        }
    }
}
