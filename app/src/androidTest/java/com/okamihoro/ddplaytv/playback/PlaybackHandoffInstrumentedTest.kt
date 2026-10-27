package com.okamihoro.ddplaytv.playback

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.media3.entity.Media3SourceType
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackHandoffInstrumentedTest {
    @Test fun encryptedRequestReentryAndRecreationKeepOneActivityAndOwnedSources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        assertTrue("Install the documented local playback fixture before running this test", fixture.isFile)
        val originalKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(PlayerType.TYPE_EXO_PLAYER.value)
        try {
            val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
            assertNotNull(provider)
            val result =
                runBlocking {
                    provider.submit(
                        PlaybackRequestInput(
                            ExternalUriDescriptor(fixture.toURI().toString(), Media3SourceType.STREAM),
                            null,
                            PlaybackRequestOrigin.EXTERNAL_INTENT
                        )
                    )
                }
            assertTrue("Encrypted request submission failed: $result", result is PlaybackSubmissionResult.Accepted)
            val intent =
                Intent(context, PlayerActivity::class.java)
                    .putExtra(PlaybackNavigation.REQUEST_ID, (result as PlaybackSubmissionResult.Accepted).requestId)
            assertEquals(setOf(PlaybackNavigation.REQUEST_ID), intent.extras!!.keySet())
            ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                awaitPlaying(scenario)
                var originalSource: BaseVideoSource? = null
                scenario.onActivity { originalSource = player(it).getVideoSource() }
                scenario.recreate()
                awaitPlaying(scenario)
                scenario.onActivity { assertSame(originalSource, player(it).getVideoSource()) }
                var retainedActivity: PlayerActivity? = null
                scenario.onActivity { retainedActivity = it }
                val replacement =
                    runBlocking {
                        provider.submit(
                            PlaybackRequestInput(
                                ExternalUriDescriptor(fixture.toURI().toString(), Media3SourceType.STREAM, displayTitle = "replacement"),
                                null,
                                PlaybackRequestOrigin.EXTERNAL_INTENT
                            )
                        )
                    } as PlaybackSubmissionResult.Accepted
                scenario.onActivity {
                    it.startActivity(
                        Intent(it, PlayerActivity::class.java)
                            .putExtra(PlaybackNavigation.REQUEST_ID, replacement.requestId)
                    )
                }
                awaitPlaying(scenario, originalSource)
                scenario.onActivity {
                    assertSame("singleTask reentry created another player Activity", retainedActivity, it)
                    assertNotSame(originalSource, player(it).getVideoSource())
                }
                instrumentation.setInTouchMode(false)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertTrue("Player focus must be visible", it.currentFocus?.isShown == true) }
                // ActivityScenario.state waits for UI idleness, including the two-second Toast.
                // Inject the remote sequence without querying scenario state between presses.
                val automation = instrumentation.uiAutomation
                repeat(4) {
                    val downTime = SystemClock.uptimeMillis()
                    assertTrue(
                        automation.injectInputEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), false)
                    )
                    assertTrue(
                        automation.injectInputEvent(KeyEvent(downTime, downTime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), false)
                    )
                    SystemClock.sleep(150)
                }
                val exitDeadline = SystemClock.elapsedRealtime() + 5000
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < exitDeadline) {
                    SystemClock.sleep(50)
                }
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally {
            PlayerConfig.putUsePlayerType(originalKernel)
        }
    }

    private fun awaitPlaying(
        scenario: ActivityScenario<PlayerActivity>,
        previousSource: BaseVideoSource? = null
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var playing = false
            scenario.onActivity {
                val player = player(it)
                playing = player.isPlaying() && player.getDuration() > 0 && player.getVideoSource() !== previousSource
            }
            if (playing) return
            SystemClock.sleep(100)
        }
        fail("Player did not start within 15 seconds")
    }

    private fun player(activity: PlayerActivity): DanDanVideoPlayer =
        activity.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer
}
