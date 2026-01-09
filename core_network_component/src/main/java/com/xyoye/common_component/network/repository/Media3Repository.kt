package com.xyoye.common_component.network.repository

import androidx.annotation.VisibleForTesting
import com.xyoye.common_component.config.Media3ToggleProvider
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.DownloadValidationResponseData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.data.media3.PlaybackSessionResponseData
import com.xyoye.data_component.entity.media3.DownloadRequiredAction
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SessionBundle
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.data_component.entity.media3.TelemetryEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmSuppressWildcards

object Media3Repository : BaseRepository() {
    private const val TAG = "Media3Repository"

    private val sessionCache = ConcurrentHashMap<String, PlaybackSession>()
    private val capabilityCache = ConcurrentHashMap<String, PlayerCapabilityContract>()
    private val toggleCache = ConcurrentHashMap<String, RolloutToggleSnapshot>()

    suspend fun createSession(request: PlaybackSessionRequestData): Result<Media3SessionBundle> =
        Result.failure(disabledException("createSession"))

    suspend fun fetchSession(
        sessionId: String,
        mediaIdHint: String? = null,
        sourceTypeHint: Media3SourceType? = null
    ): Result<Media3SessionBundle> {
        cachedBundle(sessionId)?.let { return Result.success(it) }

        return Result.failure(disabledException("fetchSession"))
    }

    suspend fun dispatchCapability(
        sessionId: String,
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>? = null
    ): Result<CapabilityCommandResponseData> {
        return Result.failure(disabledException("dispatchCapability"))
    }

    suspend fun emitTelemetry(event: TelemetryEvent): Result<Unit> =
        // Media3 网关能力当前禁用：避免在公网环境触发 DNS 解析失败/网络异常上报。
        Result.success(Unit)

    suspend fun validateDownload(request: DownloadValidationRequestData): Result<DownloadValidationResponseData> =
        // Media3 网关能力当前禁用：本地默认认为兼容，避免阻断离线播放/下载流程。
        Result.success(
            DownloadValidationResponseData(
                downloadId = request.downloadId,
                isCompatible = true,
                requiredAction = DownloadRequiredAction.NONE,
                verificationLogs = emptyList(),
            ),
        )

    fun cachedCapability(sessionId: String): PlayerCapabilityContract? = capabilityCache[sessionId]

    fun cachedToggle(sessionId: String): RolloutToggleSnapshot? = toggleCache[sessionId]

    fun cachedSession(sessionId: String): PlaybackSession? = sessionCache[sessionId]

    fun clearSession(sessionId: String) {
        sessionCache.remove(sessionId)
        capabilityCache.remove(sessionId)
        toggleCache.remove(sessionId)
    }

    @VisibleForTesting
    internal fun clearCachesForTest() {
        sessionCache.clear()
        capabilityCache.clear()
        toggleCache.clear()
    }

    private fun cacheBundle(
        response: PlaybackSessionResponseData,
        mediaId: String,
        source: Media3SourceType
    ): Media3SessionBundle {
        val session =
            PlaybackSession(
                sessionId = response.sessionId,
                mediaId = mediaId,
                sourceType = source,
                playerEngine = response.playerEngine,
                playbackState = response.playbackState,
                metrics = response.metrics,
                isOfflineCapable = response.capabilityContract.offlineSupport?.audioOnlyFallback == true,
            )
        val snapshot = Media3ToggleProvider.snapshot(appliesToSession = session.sessionId)
        sessionCache[session.sessionId] = session
        capabilityCache[session.sessionId] = response.capabilityContract
        toggleCache[session.sessionId] = snapshot
        return Media3SessionBundle(session, response.capabilityContract, snapshot)
    }

    private fun cachedBundle(sessionId: String): Media3SessionBundle? {
        val session = sessionCache[sessionId] ?: return null
        val capability = capabilityCache[sessionId] ?: return null
        val snapshot = toggleCache[sessionId] ?: return null
        return Media3SessionBundle(session, capability, snapshot)
    }

    private suspend fun <T : Any> execute(
        operation: String,
        block: suspend () -> T
    ): Result<T> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                TAG,
                operation,
                "Media3 API invocation failed",
            )
            Result.failure(NetworkException.formException(e))
        }

    private fun disabledException(operation: String): IllegalStateException {
        val snapshot = Media3ToggleProvider.snapshot()
        return IllegalStateException(
            "Media3 gateway is disabled (op=$operation, enabled=${snapshot.value}, source=${snapshot.source})",
        )
    }
}
