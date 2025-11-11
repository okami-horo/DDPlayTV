package com.xyoye.data_component.entity.media3

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TelemetryEvent(
    val eventId: String,
    val sessionId: String,
    val eventType: Media3TelemetryEventType,
    val timestamp: Long,
    val playerEngine: Media3PlayerEngine,
    val media3Version: String,
    val metrics: Map<String, Any?> = emptyMap(),
    val deviceInfo: Map<String, Any?> = emptyMap(),
    val isForeground: Boolean = true
)
