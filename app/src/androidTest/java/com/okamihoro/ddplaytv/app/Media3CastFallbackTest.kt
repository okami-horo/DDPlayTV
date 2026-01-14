package com.okamihoro.ddplaytv.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.okamihoro.ddplaytv.app.cast.Media3CastManager
import com.xyoye.data_component.entity.media3.CastTarget
import com.xyoye.data_component.entity.media3.CastTargetType
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3PlayerEngine
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.player_component.media3.fallback.CodecFallbackHandler
import com.xyoye.player_component.media3.mapper.LegacyCapabilityIssue
import com.xyoye.player_component.media3.mapper.LegacyCapabilityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@Media3Dependent("Cast manager must honor Media3 codec fallback decisions")
@RunWith(AndroidJUnit4::class)
@Ignore("Cast sender feature disabled for TV build")
class Media3CastFallbackTest {
    @Test
    fun audioOnlyFallback_isPropagatedToCastPayload() {
        val manager = Media3CastManager(CodecFallbackHandler())
        val session = playbackSession()
        val contract =
            PlayerCapabilityContract(
                sessionId = session.sessionId,
                capabilities = listOf(Media3Capability.CAST),
                castTargets =
                    listOf(
                        CastTarget(id = "living-room", name = "Living Room", type = CastTargetType.CHROMECAST),
                    ),
            )
        val codecIssues =
            LegacyCapabilityResult(
                mediaTracks = emptyList(),
                subtitleTracks = emptyList(),
                issues =
                    listOf(
                        LegacyCapabilityIssue(
                            code = "UNSUPPORTED_CODEC",
                            message = "Codec h265 not supported on cast target",
                            blocking = true,
                        ),
                    ),
            )

        val payload =
            manager.prepareCastSession(
                targetId = "living-room",
                session = session,
                capability = contract,
                capabilityResult = codecIssues,
            )

        assertTrue(payload.audioOnly)
        assertEquals("Codec h265 not supported on cast target", payload.fallbackMessage)
        assertEquals("living-room", payload.target.id)
        assertEquals(session.sessionId, payload.sessionId)
    }

    @Test
    fun fullPlayback_castsWithoutFallback() {
        val manager = Media3CastManager(CodecFallbackHandler())
        val session = playbackSession()
        val contract =
            PlayerCapabilityContract(
                sessionId = session.sessionId,
                capabilities = listOf(Media3Capability.CAST),
                castTargets =
                    listOf(
                        CastTarget(id = "office", name = "Office Display", type = CastTargetType.DLNA),
                    ),
            )

        val payload =
            manager.prepareCastSession(
                targetId = "office",
                session = session,
                capability = contract,
                capabilityResult = null,
            )

        assertFalse(payload.audioOnly)
        assertEquals("office", payload.target.id)
        assertEquals(null, payload.fallbackMessage)
    }

    private fun playbackSession(): PlaybackSession =
        PlaybackSession(
            sessionId = "session-cast",
            mediaId = "media-1",
            sourceType = Media3SourceType.STREAM,
            playerEngine = Media3PlayerEngine.MEDIA3,
        )
}
