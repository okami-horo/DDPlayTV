package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xyoye.player.kernel.inter.VideoPlayerEventListener
import com.xyoye.player.utils.PlayerConstant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Media3PreparedCallbackTest {
    @Test
    fun firstFrameBeforeReadyStillPreparesBeforeStartingPlayback() = verifyOrder(frameFirst = true)

    @Test
    fun readyBeforeFirstFramePreparesExactlyOnce() = verifyOrder(frameFirst = false)

    private fun verifyOrder(frameFirst: Boolean) {
        val events = mutableListOf<String>()
        val kernel = Media3VideoPlayer(RuntimeEnvironment.getApplication())
        kernel.setPlayerEventListener(
            object : VideoPlayerEventListener by VideoPlayerEventListener.NO_OP {
                override fun onPrepared() {
                    events += "prepared"
                }

                override fun onInfo(
                    what: Int,
                    extra: Int
                ) {
                    if (what == PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START) events += "playing"
                }
            }
        )
        val player =
            Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
                if (method.name == "getPlayWhenReady") true else null
            }
        Media3VideoPlayer::class.java
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(kernel, player)
        Media3VideoPlayer::class.java
            .getDeclaredField("isPreparing")
            .apply { isAccessible = true }
            .setBoolean(kernel, true)
        if (frameFirst) {
            kernel.onRenderedFirstFrame()
            kernel.onPlaybackStateChanged(Player.STATE_READY)
        } else {
            kernel.onPlaybackStateChanged(Player.STATE_READY)
            kernel.onRenderedFirstFrame()
        }
        assertEquals(listOf("prepared", "playing"), events)
    }
}
