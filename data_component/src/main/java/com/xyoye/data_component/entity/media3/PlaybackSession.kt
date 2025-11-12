package com.xyoye.data_component.entity.media3

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlaybackSession(
    val sessionId: String,
    val mediaId: String,
    val sourceType: Media3SourceType,
    val playerEngine: Media3PlayerEngine,
    val toggleCohort: Media3ToggleCohort? = null,
    val codecSelection: CodecSelection? = null,
    val bitrateProfile: BitrateProfile? = null,
    val playbackState: Media3PlaybackState = Media3PlaybackState.INITIALIZING,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val networkStats: NetworkStats? = null,
    val subtitleTrack: SubtitleTrack? = null,
    val availableTracks: List<MediaTrack> = emptyList(),
    val errors: List<PlaybackError> = emptyList(),
    val startedAt: Long? = null,
    val firstFrameAt: Long? = null,
    val isOfflineCapable: Boolean = false,
    val metrics: PlaybackSessionMetrics? = null
)

@JsonClass(generateAdapter = true)
data class CodecSelection(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val profile: String? = null,
    val level: String? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class BitrateProfile(
    val minBitrateKbps: Long? = null,
    val maxBitrateKbps: Long? = null,
    val targetBitrateKbps: Long? = null
)

@JsonClass(generateAdapter = true)
data class NetworkStats(
    val averageBandwidthKbps: Long? = null,
    val peakBandwidthKbps: Long? = null,
    val packetLossPercent: Float? = null,
    val roundTripTimeMs: Long? = null
)

@JsonClass(generateAdapter = true)
data class SubtitleTrack(
    val id: String,
    val language: String,
    val format: String,
    val offsetMs: Long? = null,
    val isDefault: Boolean = false
)

@JsonClass(generateAdapter = true)
data class MediaTrack(
    val id: String,
    val label: String,
    val language: String? = null,
    val type: MediaTrackType = MediaTrackType.VIDEO,
    val bitrateKbps: Long? = null,
    val isDefault: Boolean = false
)

@JsonClass(generateAdapter = true)
data class PlaybackError(
    val code: String,
    val message: String,
    val fatal: Boolean
)

@JsonClass(generateAdapter = true)
data class PlaybackSessionMetrics(
    val firstFrameTargetMs: Long? = null
)
