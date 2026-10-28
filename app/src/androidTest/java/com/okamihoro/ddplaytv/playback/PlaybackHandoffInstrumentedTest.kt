package com.okamihoro.ddplaytv.playback

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.crypto.KeystoreAesGcmCrypto
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackHandoffInstrumentedTest {
    @Test fun completedSeekImmediatelyPersistsEncryptedRecoveryPosition() = verifySeek(PlayerType.TYPE_EXO_PLAYER, "Media3VideoPlayer")

    @Test fun vlcCompletedSeekPersistsRecoveryPosition() = verifySeek(PlayerType.TYPE_VLC_PLAYER, "VlcVideoPlayer")

    @Test fun mpvCompletedSeekPersistsRecoveryPosition() = verifySeek(PlayerType.TYPE_MPV_PLAYER, "MpvVideoPlayer")

    private fun verifySeek(
        kernelType: PlayerType,
        kernelClass: String
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        assertTrue(fixture.isFile)
        val originalKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(kernelType.value)
        try {
            val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
            val request =
                runBlocking {
                    provider.submit(
                        PlaybackRequestInput(
                            ExternalUriDescriptor(Uri.fromFile(fixture).toString(), Media3SourceType.STREAM),
                            null,
                            PlaybackRequestOrigin.EXTERNAL_INTENT
                        )
                    )
                } as PlaybackSubmissionResult.Accepted
            ActivityScenario
                .launch<PlayerActivity>(
                    Intent(context, PlayerActivity::class.java)
                        .putExtra(PlaybackNavigation.REQUEST_ID, request.requestId)
                ).use { scenario ->
                    awaitPlaying(scenario)
                    scenario.onActivity {
                        val kernel =
                            DanDanVideoPlayer::class.java
                                .getDeclaredField("mVideoPlayer")
                                .apply { isAccessible = true }
                                .get(player(it))
                        assertEquals("Kernel fallback is not evidence of the requested kernel", kernelClass, kernel.javaClass.simpleName)
                    }
                    // Seeking during playback must save without a pause event or the five-second ticker.
                    scenario.onActivity { player(it).seekTo(9000) }
                    awaitSnapshot { it.getLong("positionMs") >= 8500 }
                    scenario.onActivity { player(it).seekTo(18000) }
                    awaitSnapshot { it.getLong("positionMs") >= 17500 }
                }
        } finally {
            PlayerConfig.putUsePlayerType(originalKernel)
        }
    }

    private fun awaitSnapshot(matches: (JSONObject) -> Boolean) {
        val cipher = KeystoreAesGcmCrypto("dandanplay.playback.handoff.aes", rsaKeyAlias = "dandanplay.playback.handoff.rsa")
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < deadline) {
            val encrypted = MMKV.mmkvWithID("dandanplay.playback.handoff").decodeString("state")
            val target =
                encrypted
                    ?.let(cipher::decrypt)
                    ?.let(::JSONObject)
                    ?.optJSONObject("snapshot")
                    ?.optJSONObject("target")
            if (target != null && matches(target)) return
            SystemClock.sleep(25)
        }
        fail("Expected an immediate encrypted snapshot update before the five-second ticker")
    }

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
                var activeSource: BaseVideoSource? = null
                scenario.onActivity {
                    activeSource = player(it).getVideoSource()
                    it.startActivity(
                        Intent(it, PlayerActivity::class.java)
                            .putExtra(PlaybackNavigation.REQUEST_ID, "missing-request")
                    )
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertSame("Rejected replacement retired the active source", activeSource, player(it).getVideoSource())
                }
                // singleTask reentry pauses playback; wait until its window can receive remote input.
                val returnDeadline = SystemClock.elapsedRealtime() + 5000
                var readyForInput = false
                while (!readyForInput && SystemClock.elapsedRealtime() < returnDeadline) {
                    scenario.onActivity {
                        readyForInput = it.hasWindowFocus() && it.intent.getStringExtra(PlaybackNavigation.REQUEST_ID) == "missing-request"
                    }
                    if (!readyForInput) SystemClock.sleep(50)
                }
                assertTrue("Reentered player window must receive input", readyForInput)
                instrumentation.setInTouchMode(false)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitPlaying(scenario)
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
