package com.xyoye.player_component.media3.mapper

import com.xyoye.data_component.entity.media3.MediaTrack
import com.xyoye.data_component.entity.media3.MediaTrackType
import com.xyoye.data_component.entity.media3.SubtitleTrack
import java.util.Locale

class LegacyCapabilityMapper(
    private val supportedVideoMimeTypes: Set<String> = SUPPORTED_VIDEO_MIME_TYPES,
    private val supportedAudioMimeTypes: Set<String> = SUPPORTED_AUDIO_MIME_TYPES,
    private val supportedSubtitleFormats: Set<String> = SUPPORTED_SUBTITLE_FORMATS,
    private val supportedDrmSchemes: Set<String> = SUPPORTED_DRM_SCHEMES
) {
    fun map(input: LegacyCapabilityInput): LegacyCapabilityResult {
        val mediaTracks = mutableListOf<MediaTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()
        val issues = mutableListOf<LegacyCapabilityIssue>()

        input.renderers.forEach { renderer ->
            val mime = renderer.mimeType.lowercase(Locale.US)
            val type =
                when {
                    mime.startsWith("video/") -> MediaTrackType.VIDEO
                    mime.startsWith("audio/") -> MediaTrackType.AUDIO
                    else -> null
                }
            val supported =
                when (type) {
                    MediaTrackType.VIDEO -> supportedVideoMimeTypes.contains(mime)
                    MediaTrackType.AUDIO -> supportedAudioMimeTypes.contains(mime)
                    else -> false
                }

            if (supported && type != null) {
                mediaTracks +=
                    MediaTrack(
                        id = renderer.name,
                        label = renderer.name,
                        language = renderer.language,
                        type = type,
                        bitrateKbps = renderer.bitrateKbps,
                        isDefault = renderer.isDefault,
                    )
            } else {
                issues +=
                    LegacyCapabilityIssue(
                        code = "UNSUPPORTED_CODEC",
                        message = "Renderer ${renderer.name} uses unsupported mime ${renderer.mimeType}",
                        blocking = type == MediaTrackType.VIDEO,
                    )
            }
        }

        val drmRequired = input.renderers.any { it.drmRequired }
        if (drmRequired) {
            val resolvedScheme =
                input.drmSchemes.firstOrNull {
                    supportedDrmSchemes.contains(it.lowercase(Locale.US))
                }
            if (resolvedScheme == null) {
                issues +=
                    LegacyCapabilityIssue(
                        code = "DRM_UNSUPPORTED",
                        message = "None of the declared DRM schemes (${input.drmSchemes.joinToString()}) are supported by Media3",
                        blocking = true,
                    )
            }
        }

        input.subtitles.forEach { subtitle ->
            val normalizedFormat = subtitle.format.lowercase(Locale.US)
            if (supportedSubtitleFormats.contains(normalizedFormat)) {
                subtitleTracks +=
                    SubtitleTrack(
                        id = subtitle.id,
                        language = subtitle.language,
                        format = normalizedFormat,
                        offsetMs = subtitle.offsetMs,
                        isDefault = subtitle.isDefault,
                    )
            } else {
                issues +=
                    LegacyCapabilityIssue(
                        code = "SUBTITLE_UNSUPPORTED",
                        message = "Subtitle ${subtitle.id} format ${subtitle.format} is not supported by Media3",
                        blocking = false,
                    )
            }
        }

        return LegacyCapabilityResult(
            mediaTracks = mediaTracks,
            subtitleTracks = subtitleTracks,
            issues = issues,
        )
    }

    companion object {
        private val SUPPORTED_VIDEO_MIME_TYPES =
            setOf(
                "video/avc",
                "video/hevc",
                "video/mp4v-es",
            )

        private val SUPPORTED_AUDIO_MIME_TYPES =
            setOf(
                "audio/mp4a-latm",
                "audio/ac3",
                "audio/eac3",
                "audio/opus",
            )

        private val SUPPORTED_SUBTITLE_FORMATS =
            setOf(
                "srt",
                "ass",
                "ssa",
                "vtt",
            )

        private val SUPPORTED_DRM_SCHEMES =
            setOf(
                "widevine",
                "playready",
            )
    }
}

data class LegacyCapabilityInput(
    val renderers: List<LegacyRendererConfig> = emptyList(),
    val subtitles: List<LegacySubtitleConfig> = emptyList(),
    val drmSchemes: List<String> = emptyList()
)

data class LegacyRendererConfig(
    val name: String,
    val mimeType: String,
    val language: String? = null,
    val bitrateKbps: Long? = null,
    val drmRequired: Boolean = false,
    val isDefault: Boolean = false
)

data class LegacySubtitleConfig(
    val id: String,
    val language: String,
    val format: String,
    val offsetMs: Long? = null,
    val isDefault: Boolean = false
)

data class LegacyCapabilityResult(
    val mediaTracks: List<MediaTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val issues: List<LegacyCapabilityIssue>
) {
    val blockingIssues: List<LegacyCapabilityIssue> = issues.filter { it.blocking }
    val hasBlockingIssue: Boolean = blockingIssues.isNotEmpty()
}

data class LegacyCapabilityIssue(
    val code: String,
    val message: String,
    val blocking: Boolean
)
