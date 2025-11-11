package com.xyoye.common_component.network.repository

import com.xyoye.common_component.BuildConfig
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.entity.media3.Media3TelemetryEventType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.data_component.entity.media3.TelemetryEvent
import com.xyoye.data_component.media3.mapper.TelemetryEventMapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface Media3TelemetrySink {
    suspend fun recordStartup(
        session: PlaybackSession,
        snapshot: RolloutToggleSnapshot?,
        autoplay: Boolean
    )

    suspend fun recordFirstFrame(session: PlaybackSession, latencyMs: Long)

    suspend fun recordError(session: PlaybackSession, throwable: Throwable)

    suspend fun recordCastTransfer(session: PlaybackSession, targetId: String?)
}

/**
 * Buffers telemetry events and flushes them in small batches to reduce network chatter.
 * When a flush fails, events are re-queued so they can be retried on the next trigger.
 */
class Media3TelemetryRepository(
    private val mapper: TelemetryEventMapper = TelemetryEventMapper(
        media3VersionProvider = { BuildConfig.MEDIA3_VERSION }
    ),
    private val emitter: suspend (TelemetryEvent) -> Result<Unit> = { event ->
        Media3Repository.emitTelemetry(event)
    },
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) : Media3TelemetrySink {

    private val mutex = Mutex()
    private val pending = ArrayDeque<TelemetryEvent>()

    override suspend fun recordStartup(
        session: PlaybackSession,
        snapshot: RolloutToggleSnapshot?,
        autoplay: Boolean
    ) {
        val metrics = mutableMapOf<String, Any?>(
            "autoplay" to autoplay,
            "sourceType" to session.sourceType.name
        )
        snapshot?.let {
            metrics["toggleSource"] = it.source.name
            metrics["toggleValue"] = it.value
        }
        enqueue(session, Media3TelemetryEventType.STARTUP, metrics)
    }

    override suspend fun recordFirstFrame(session: PlaybackSession, latencyMs: Long) {
        enqueue(
            session = session,
            eventType = Media3TelemetryEventType.FIRST_FRAME,
            metrics = mapOf("latencyMs" to latencyMs),
            flush = true
        )
    }

    override suspend fun recordError(session: PlaybackSession, throwable: Throwable) {
        val metrics = mapOf(
            "errorMessage" to (throwable.message ?: "unknown"),
            "errorType" to throwable::class.java.simpleName
        )
        enqueue(
            session = session,
            eventType = Media3TelemetryEventType.ERROR,
            metrics = metrics,
            flush = true
        )
    }

    override suspend fun recordCastTransfer(session: PlaybackSession, targetId: String?) {
        if (targetId.isNullOrBlank()) {
            return
        }
        enqueue(
            session = session,
            eventType = Media3TelemetryEventType.CAST_TRANSFER,
            metrics = mapOf("targetId" to targetId),
            flush = true
        )
    }

    suspend fun flush() {
        mutex.withLock {
            flushLocked()
        }
    }

    private suspend fun enqueue(
        session: PlaybackSession,
        eventType: Media3TelemetryEventType,
        metrics: Map<String, Any?> = emptyMap(),
        deviceInfo: Map<String, Any?> = emptyMap(),
        isForeground: Boolean = true,
        flush: Boolean = false
    ) {
        mutex.withLock {
            val event = mapper.createEvent(
                session = session,
                eventType = eventType,
                metrics = metrics,
                deviceInfo = deviceInfo,
                isForeground = isForeground
            )
            pending += event
            if (pending.size >= batchSize || flush) {
                flushLocked()
            }
        }
    }

    private suspend fun flushLocked() {
        if (pending.isEmpty()) {
            return
        }
        val queue = ArrayDeque<TelemetryEvent>()
        queue.addAll(pending)
        pending.clear()
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            val result = emitter(event)
            if (result.isFailure) {
                val throwable = result.exceptionOrNull()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    throwable ?: IllegalStateException("Telemetry emit failed"),
                    TAG,
                    "flush",
                    "eventType=${event.eventType}"
                )
                pending.addFirst(event)
                pending.addAll(queue)
                break
            }
        }
    }

    companion object {
        private const val TAG = "Media3TelemetryRepo"
        private const val DEFAULT_BATCH_SIZE = 5
    }
}
