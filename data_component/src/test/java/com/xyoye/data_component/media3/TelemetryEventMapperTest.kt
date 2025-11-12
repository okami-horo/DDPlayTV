package com.xyoye.data_component.media3

import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.data_component.entity.media3.Media3PlayerEngine
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.Media3TelemetryEventType
import com.xyoye.data_component.entity.media3.Media3ToggleCohort
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlaybackSessionMetrics
import com.xyoye.data_component.media3.mapper.TelemetryEventMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@Media3Dependent("Telemetry mapper encodes Media3 session identifiers")
class TelemetryEventMapperTest {

    private val mapper = TelemetryEventMapper(
        media3VersionProvider = { "1.8.0-test" },
        idProvider = { "event-id-123" },
        timestampProvider = { 42L }
    )

    @Test
    fun createEvent_injectsIdentifiersAndSessionMetadata() {
        val session = PlaybackSession(
            sessionId = "session-1",
            mediaId = "media-9",
            sourceType = Media3SourceType.STREAM,
            playerEngine = Media3PlayerEngine.MEDIA3,
            toggleCohort = Media3ToggleCohort.TREATMENT,
            metrics = PlaybackSessionMetrics(firstFrameTargetMs = 2_000)
        )
        val metricsInput = mapOf("startupMs" to 1_200L)
        val deviceInfo = mapOf("device" to "Pixel 9")

        val event = mapper.createEvent(
            session = session,
            eventType = Media3TelemetryEventType.STARTUP,
            metrics = metricsInput,
            deviceInfo = deviceInfo,
            isForeground = false
        )

        assertEquals("event-id-123", event.eventId)
        assertEquals("session-1", event.sessionId)
        assertEquals(Media3TelemetryEventType.STARTUP, event.eventType)
        assertEquals(42L, event.timestamp)
        assertEquals(Media3PlayerEngine.MEDIA3, event.playerEngine)
        assertEquals("1.8.0-test", event.media3Version)
        assertEquals(deviceInfo, event.deviceInfo)
        assertFalse(event.isForeground)

        assertEquals(1_200L, event.metrics["startupMs"])
        assertEquals("TREATMENT", event.metrics["toggleCohort"])
        assertEquals(2_000L, event.metrics["firstFrameBudgetMs"])
    }

    @Test
    fun createEvent_doesNotMutateProvidedMetricMap() {
        val session = PlaybackSession(
            sessionId = "session-2",
            mediaId = "media-101",
            sourceType = Media3SourceType.DOWNLOAD,
            playerEngine = Media3PlayerEngine.EXO_LEGACY
        )
        val metricsInput = mutableMapOf<String, Any?>("bufferingRatio" to 0.12)

        val event = mapper.createEvent(
            session = session,
            eventType = Media3TelemetryEventType.BUFFERING,
            metrics = metricsInput
        )

        assertEquals(
            mapOf(
                "bufferingRatio" to 0.12,
                "toggleCohort" to "UNKNOWN"
            ),
            event.metrics
        )
        assertEquals(mapOf("bufferingRatio" to 0.12), metricsInput)
    }
}
