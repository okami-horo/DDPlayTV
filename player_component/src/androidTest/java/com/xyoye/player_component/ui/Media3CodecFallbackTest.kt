package com.xyoye.player_component.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.player_component.media3.fallback.CodecFallbackDecision
import com.xyoye.player_component.media3.fallback.CodecFallbackHandler
import com.xyoye.player_component.media3.mapper.LegacyCapabilityIssue
import com.xyoye.player_component.media3.mapper.LegacyCapabilityResult
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Media3Dependent("Validates codec fallback decisions for Media3 pipelines")
@RunWith(AndroidJUnit4::class)
class Media3CodecFallbackTest {

    @Test
    fun unsupportedCodec_forcesAudioOnlyFallback() {
        val handler = CodecFallbackHandler()
        val result = handler.evaluate(
            LegacyCapabilityResult(
                mediaTracks = emptyList(),
                subtitleTracks = emptyList(),
                issues = listOf(
                    LegacyCapabilityIssue(
                        code = "UNSUPPORTED_CODEC",
                        message = "video/av1 is unavailable",
                        blocking = true
                    )
                )
            )
        )

        assertTrue(result is CodecFallbackDecision.AudioOnly)
    }

    @Test
    fun noBlockingIssues_retainsFullPlayback() {
        val handler = CodecFallbackHandler()
        val result = handler.evaluate(
            LegacyCapabilityResult(
                mediaTracks = emptyList(),
                subtitleTracks = emptyList(),
                issues = emptyList()
            )
        )

        assertTrue(result is CodecFallbackDecision.None)
    }
}
