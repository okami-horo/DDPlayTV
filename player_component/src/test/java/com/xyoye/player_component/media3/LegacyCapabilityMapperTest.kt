package com.xyoye.player_component.media3

import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.player_component.media3.mapper.LegacyCapabilityInput
import com.xyoye.player_component.media3.mapper.LegacyCapabilityMapper
import com.xyoye.player_component.media3.mapper.LegacyRendererConfig
import com.xyoye.player_component.media3.mapper.LegacySubtitleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Media3Dependent("Mapper enforces codec/subtitle compatibility for Media3 capabilities")
class LegacyCapabilityMapperTest {

    private val mapper = LegacyCapabilityMapper()

    @Test
    fun map_returnsTracks_whenAllInputsSupported() {
        val input = LegacyCapabilityInput(
            renderers = listOf(
                LegacyRendererConfig(
                    name = "video-main",
                    mimeType = "video/avc",
                    bitrateKbps = 4200,
                    drmRequired = true
                ),
                LegacyRendererConfig(
                    name = "audio-main",
                    mimeType = "audio/mp4a-latm",
                    language = "zh",
                    isDefault = true
                )
            ),
            subtitles = listOf(
                LegacySubtitleConfig(
                    id = "sub-ass",
                    language = "zh",
                    format = "ass",
                    offsetMs = 120
                )
            ),
            drmSchemes = listOf("widevine")
        )

        val result = mapper.map(input)

        assertFalse(result.hasBlockingIssue)
        assertEquals(2, result.mediaTracks.size)
        assertEquals("video-main", result.mediaTracks.first().id)
        assertEquals("ass", result.subtitleTracks.first().format)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun map_flagsBlockingIssues_whenCodecOrDrmUnsupported() {
        val input = LegacyCapabilityInput(
            renderers = listOf(
                LegacyRendererConfig(
                    name = "video-av1",
                    mimeType = "video/av1",
                    drmRequired = true
                )
            ),
            drmSchemes = listOf("fairplay")
        )

        val result = mapper.map(input)

        assertTrue(result.hasBlockingIssue)
        assertTrue(result.issues.any { it.code == "UNSUPPORTED_CODEC" })
        assertTrue(result.issues.any { it.code == "DRM_UNSUPPORTED" })
    }

    @Test
    fun map_reportsNonBlockingSubtitleIssues() {
        val input = LegacyCapabilityInput(
            renderers = listOf(
                LegacyRendererConfig(
                    name = "video-main",
                    mimeType = "video/avc"
                )
            ),
            subtitles = listOf(
                LegacySubtitleConfig(
                    id = "sub-ttml",
                    language = "jp",
                    format = "ttml"
                )
            )
        )

        val result = mapper.map(input)

        assertFalse(result.hasBlockingIssue)
        assertTrue(result.issues.any { it.code == "SUBTITLE_UNSUPPORTED" })
        assertTrue(result.subtitleTracks.isEmpty())
    }
}
