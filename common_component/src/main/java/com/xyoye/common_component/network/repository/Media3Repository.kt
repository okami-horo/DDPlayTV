package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.data.media3.CapabilityCommandRequestData
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.DownloadValidationResponseData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.data.media3.PlaybackSessionResponseData
import com.xyoye.data_component.data.media3.RolloutTogglePatchData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.data_component.entity.media3.TelemetryEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmSuppressWildcards

object Media3Repository : BaseRepository() {

    private const val TAG = "Media3Repository"

    private val sessionCache = ConcurrentHashMap<String, PlaybackSession>()
    private val capabilityCache = ConcurrentHashMap<String, PlayerCapabilityContract>()
    private val toggleCache = ConcurrentHashMap<String, RolloutToggleSnapshot>()

    suspend fun createSession(
        request: PlaybackSessionRequestData
    ): Result<Media3SessionBundle> {
        return execute("createSession") {
            Retrofit.media3Service.createSession(request)
        }.map {
            cacheBundle(it, request.mediaId, request.sourceType)
        }
    }

    suspend fun fetchSession(
        sessionId: String,
        mediaIdHint: String? = null,
        sourceTypeHint: Media3SourceType? = null
    ): Result<Media3SessionBundle> {
        cachedBundle(sessionId)?.let { return Result.success(it) }

        return execute("fetchSession") {
            Retrofit.media3Service.fetchSession(sessionId)
        }.map {
            val mediaId = it.mediaId ?: mediaIdHint ?: sessionCache[sessionId]?.mediaId ?: ""
            val source = it.sourceType ?: sourceTypeHint ?: sessionCache[sessionId]?.sourceType
                ?: Media3SourceType.STREAM
            cacheBundle(it, mediaId, source)
        }
    }

    suspend fun dispatchCapability(
        sessionId: String,
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>? = null
    ): Result<CapabilityCommandResponseData> {
        val request = CapabilityCommandRequestData(capability, payload)
        return execute("dispatchCapability") {
            Retrofit.media3Service.dispatchCommand(sessionId, request)
        }.onSuccess {
            val newState = it.resultingState
            if (newState != null) {
                sessionCache[sessionId]?.let { session ->
                    sessionCache[sessionId] = session.copy(playbackState = newState)
                }
            }
        }
    }

    suspend fun emitTelemetry(event: TelemetryEvent): Result<Unit> {
        return execute("emitTelemetry") {
            Retrofit.media3Service.emitTelemetry(event)
        }.map { }
    }

    suspend fun updateRollout(patch: RolloutTogglePatchData): Result<RolloutToggleSnapshot> {
        return execute("updateRollout") {
            Retrofit.media3Service.updateRollout(patch)
        }.onSuccess { snapshot ->
            snapshot.appliesToSession?.let { toggleCache[it] = snapshot }
        }
    }

    suspend fun validateDownload(
        request: DownloadValidationRequestData
    ): Result<DownloadValidationResponseData> {
        return execute("validateDownload") {
            Retrofit.media3Service.validateDownload(request)
        }
    }

    fun cachedCapability(sessionId: String): PlayerCapabilityContract? = capabilityCache[sessionId]

    fun cachedToggle(sessionId: String): RolloutToggleSnapshot? = toggleCache[sessionId]

    fun cachedSession(sessionId: String): PlaybackSession? = sessionCache[sessionId]

    fun clearSession(sessionId: String) {
        sessionCache.remove(sessionId)
        capabilityCache.remove(sessionId)
        toggleCache.remove(sessionId)
    }

    private fun cacheBundle(
        response: PlaybackSessionResponseData,
        mediaId: String,
        source: Media3SourceType
    ): Media3SessionBundle {
        val session = PlaybackSession(
            sessionId = response.sessionId,
            mediaId = mediaId,
            sourceType = source,
            playerEngine = response.playerEngine,
            playbackState = response.playbackState,
            metrics = response.metrics,
            isOfflineCapable = response.capabilityContract.offlineSupport?.audioOnlyFallback == true
        )
        sessionCache[session.sessionId] = session
        capabilityCache[session.sessionId] = response.capabilityContract
        toggleCache[session.sessionId] = response.toggleSnapshot
        return Media3SessionBundle(session, response.capabilityContract, response.toggleSnapshot)
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
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                TAG,
                operation,
                "Media3 API invocation failed"
            )
            Result.failure(NetworkException.formException(e))
        }
    }
}

data class Media3SessionBundle(
    val session: PlaybackSession,
    val capabilityContract: PlayerCapabilityContract,
    val toggleSnapshot: RolloutToggleSnapshot
)
