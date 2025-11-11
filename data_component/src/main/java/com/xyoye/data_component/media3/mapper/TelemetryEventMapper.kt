package com.xyoye.data_component.media3.mapper

import com.xyoye.data_component.entity.media3.Media3TelemetryEventType
import com.xyoye.data_component.entity.media3.Media3ToggleCohort
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.TelemetryEvent
import java.util.UUID

/**
 * Builds telemetry payloads while injecting mandatory identifiers (session id,
 * player engine, version, and rollout cohort metadata).
 */
class TelemetryEventMapper(
    private val media3VersionProvider: () -> String,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
    private val timestampProvider: () -> Long = { System.currentTimeMillis() }
) {

    fun createEvent(
        session: PlaybackSession,
        eventType: Media3TelemetryEventType,
        metrics: Map<String, Any?> = emptyMap(),
        deviceInfo: Map<String, Any?> = emptyMap(),
        isForeground: Boolean = true
    ): TelemetryEvent {
        val enrichedMetrics = LinkedHashMap<String, Any?>(metrics)
        enrichedMetrics.putIfAbsent("toggleCohort", cohortValue(session.toggleCohort))
        session.metrics?.firstFrameTargetMs?.let {
            enrichedMetrics.putIfAbsent("firstFrameBudgetMs", it)
        }

        return TelemetryEvent(
            eventId = idProvider(),
            sessionId = session.sessionId,
            eventType = eventType,
            timestamp = timestampProvider(),
            playerEngine = session.playerEngine,
            media3Version = media3VersionProvider(),
            metrics = enrichedMetrics.toMap(),
            deviceInfo = deviceInfo.toMap(),
            isForeground = isForeground
        )
    }

    private fun cohortValue(cohort: Media3ToggleCohort?): String {
        return cohort?.name ?: "UNKNOWN"
    }
}
