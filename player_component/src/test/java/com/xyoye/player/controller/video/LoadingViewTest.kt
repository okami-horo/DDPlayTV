package com.xyoye.player.controller.video

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.xyoye.data_component.enums.PlayState
import com.xyoye.player_component.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LoadingViewTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var loading: LoadingView
    private val panel get() = loading.findViewById<View>(R.id.loading_panel)
    private val text get() = loading.findViewById<TextView>(R.id.loading_text).text.toString()

    @Before fun createView() {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        loading = LoadingView(activity.get())
        activity.get().setContentView(loading)
    }

    @After fun destroyView() {
        activity.pause().stop().destroy()
    }

    @Test fun fastPreparationDoesNotFlashALoadingPanel() {
        loading.onPlayStateChanged(PlayState.STATE_PREPARING)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertFalse(panel.isShown)
        loading.onPlayStateChanged(PlayState.STATE_PLAYING)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertFalse(panel.isShown)
    }

    @Test fun slowPreparationStaysVisibleUntilTheFirstRenderedFrame() {
        loading.onPlayStateChanged(PlayState.STATE_PREPARING)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(panel.isShown)
        assertEquals("正在准备播放…", text)
        loading.onPlayStateChanged(PlayState.STATE_PREPARED)
        assertTrue("Prepared is not evidence that the first frame is visible", panel.isShown)
        loading.onPlayStateChanged(PlayState.STATE_PLAYING)
        assertFalse(panel.isShown)
    }

    @Test fun pausedFirstFrameAlsoEndsPreparation() {
        loading.onPlayStateChanged(PlayState.STATE_PREPARING)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        loading.onPlayStateChanged(PlayState.STATE_PREPARED)
        loading.onPlayStateChanged(PlayState.STATE_PAUSED)
        assertFalse(panel.isShown)
    }

    @Test fun initialSeekBufferingDoesNotDismissPreparationBeforeTheFirstFrame() {
        loading.onPlayStateChanged(PlayState.STATE_PREPARING)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        loading.onPlayStateChanged(PlayState.STATE_PREPARED)
        loading.onPlayStateChanged(PlayState.STATE_BUFFERING_PAUSED)
        assertEquals("正在准备播放…", text)
        loading.onPlayStateChanged(PlayState.STATE_BUFFERING_PLAYING)
        assertTrue("Initial buffering completion is not a rendered frame", panel.isShown)
        loading.onPlayStateChanged(PlayState.STATE_PLAYING)
        assertFalse(panel.isShown)
    }

    @Test fun rebufferingKeepsFeedbackAndClearsOnRecovery() {
        loading.onPlayStateChanged(PlayState.STATE_PLAYING)
        loading.onPlayStateChanged(PlayState.STATE_BUFFERING_PAUSED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(panel.isShown)
        assertEquals("正在缓冲…", text)
        loading.onPlayStateChanged(PlayState.STATE_BUFFERING_PLAYING)
        assertFalse(panel.isShown)
    }

    @Test fun failureAndExitCancelPendingFeedback() {
        for (terminal in listOf(PlayState.STATE_ERROR, PlayState.STATE_START_ABORT, PlayState.STATE_IDLE, PlayState.STATE_COMPLETED)) {
            loading.onPlayStateChanged(PlayState.STATE_PREPARING)
            loading.onPlayStateChanged(terminal)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertFalse("Delayed loading survived $terminal", panel.isShown)
        }
    }

    @Test fun sourceAndKernelPreparationKeepTheSamePanelVisibleAcrossDetach() {
        loading.setSourcePreparing(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(panel.isShown)
        loading.onPlayStateChanged(PlayState.STATE_IDLE)
        assertTrue(panel.isShown)
        loading.onPlayStateChanged(PlayState.STATE_PREPARING)
        loading.setSourcePreparing(false)
        assertTrue(panel.isShown)
        assertEquals("正在准备播放…", text)
        loading.onPlayStateChanged(PlayState.STATE_PREPARED)
        assertTrue(panel.isShown)
        loading.onPlayStateChanged(PlayState.STATE_PLAYING)
        assertFalse(panel.isShown)
    }

    @Test fun repeatedPreparationDoesNotRestartTheDisplayDelay() {
        loading.setSourcePreparing(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        loading.onPlayStateChanged(PlayState.STATE_PREPARING)
        loading.setSourcePreparing(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110))
        assertTrue(panel.isShown)
    }

    @Test fun failedReplacementLeavesNoLoadingOverTheOriginalPlayback() {
        loading.onPlayStateChanged(PlayState.STATE_PLAYING)
        loading.setSourcePreparing(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(panel.isShown)
        loading.setSourcePreparing(false)
        assertFalse(panel.isShown)
    }
}
