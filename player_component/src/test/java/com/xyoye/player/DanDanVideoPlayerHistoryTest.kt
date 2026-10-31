package com.xyoye.player

import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.PlayState
import com.xyoye.player.kernel.impl.media3.Media3VideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerUiTestSource
import com.xyoye.player_component.utils.PlayRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [DanDanVideoPlayerHistoryTest.Recorder::class])
class DanDanVideoPlayerHistoryTest {
    @After fun clearRecords() {
        Recorder.progress.clear()
    }

    @Test fun pauseAfterExitCannotOverwriteTheSavedPositionWithReleasedKernelZero() {
        val player = playerAt(18_000)
        player.recordPlayInfo()
        assertTrue(player.release())

        // PlayerActivity.finish() detaches before Android delivers onPause().
        player.pause()
        player.recordPlayInfo()

        assertEquals(listOf(18_000L to 60_000L), Recorder.progress)
    }

    @Test fun anAssignedSourceWithoutPlaybackDoesNotEraseItsExistingHistory() {
        val player = DanDanVideoPlayer(Robolectric.buildActivity(AppCompatActivity::class.java).get())
        player.setVideoSource(PlayerUiTestSource("episode", historyPosition = 18_000))
        player.recordPlayInfo()
        assertTrue(Recorder.progress.isEmpty())
    }

    @Test fun aRealZeroPositionRemainsRecordableWhilePaused() {
        val player = playerAt(0)
        player.pause()
        player.recordPlayInfo()
        assertEquals(listOf(0L to 60_000L), Recorder.progress)
    }

    @Test fun aPlaybackErrorStillRecordsTheLastKnownPosition() {
        val player = playerAt(18_000)
        assertEquals(18_000L, player.getCurrentPosition())
        state(player, PlayState.STATE_ERROR)
        player.recordPlayInfo()
        assertEquals(18_000L, Recorder.progress.single().first)
    }

    private fun playerAt(position: Long): DanDanVideoPlayer {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        val player = DanDanVideoPlayer(activity)
        val exo =
            Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
                when (method.name) {
                    "getContentPosition" -> position
                    "getDuration" -> 60_000L
                    "isPlaying" -> true
                    "getPlayWhenReady" -> true
                    "getPlaybackState" -> Player.STATE_READY
                    "getApplicationLooper" -> Looper.getMainLooper()
                    else -> null
                }
            }
        val kernel = Media3VideoPlayer(activity)
        Media3VideoPlayer::class.java
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(kernel, exo)
        DanDanVideoPlayer::class.java
            .getDeclaredField("mVideoPlayer")
            .apply { isAccessible = true }
            .set(player, kernel)
        player.setVideoSource(PlayerUiTestSource("episode", historyPosition = 18_000))
        state(player, PlayState.STATE_PLAYING)
        return player
    }

    private fun state(
        player: DanDanVideoPlayer,
        value: PlayState
    ) {
        DanDanVideoPlayer::class.java
            .getDeclaredField("mCurrentPlayState")
            .apply { isAccessible = true }
            .set(player, value)
    }

    @Implements(value = PlayRecorder::class, isInAndroidSdk = false)
    class Recorder {
        @Implementation fun recordProgress(
            source: BaseVideoSource,
            position: Long,
            duration: Long
        ) {
            progress += position to duration
        }

        companion object {
            val progress = mutableListOf<Pair<Long, Long>>()
        }
    }
}
