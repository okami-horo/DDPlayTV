package com.okamihoro.ddplaytv.playback

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.source.handoff.PlaybackReferenceFactory
import com.xyoye.common_component.storage.impl.WebDavStorage
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackLoadingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun media3UsesOneLoadingPanelAcrossSourceAndKernelPreparation() = verifyPreparation(PlayerType.TYPE_EXO_PLAYER)

    @Test fun mpvUsesOneLoadingPanelAcrossSourceAndKernelPreparation() = verifyPreparation(PlayerType.TYPE_MPV_PLAYER)

    @Test fun remoteBackCancelsSourcePreparationWithoutStartingTheKernel() = verifyPreparation(PlayerType.TYPE_EXO_PLAYER, cancel = true)

    private fun verifyPreparation(
        kernel: PlayerType,
        cancel: Boolean = false
    ) {
        val originalKernel = PlayerConfig.getUsePlayerType()
        val video = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        assertTrue("Install the documented playback fixture", video.isFile)
        val server = GatedVideoServer(video)
        server.start()
        val dao = DatabaseProvider.instance.getMediaLibraryDao()
        val input = MediaLibraryEntity(displayName = "UX01 fixture", mediaType = MediaType.WEBDAV_SERVER, url = server.baseUrl)
        runBlocking { dao.insert(input) }
        val library = runBlocking { dao.getByUrl(input.url, input.mediaType)!! }
        val storage = WebDavStorage(library)
        PlayerConfig.putUsePlayerType(kernel.value)
        try {
            val descriptor = runBlocking { PlaybackReferenceFactory.fromFile(storage.pathFile("/loading.mp4", false)) }
            val service = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
            val request =
                runBlocking {
                    service.submit(PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.STORAGE_FILE))
                } as PlaybackSubmissionResult.Accepted
            ActivityScenario
                .launch<PlayerActivity>(
                    Intent(context, PlayerActivity::class.java).putExtra(PlaybackNavigation.REQUEST_ID, request.requestId)
                ).use { scenario ->
                    lateinit var activity: PlayerActivity
                    instrumentation.runOnMainSync {
                        activity =
                            ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<PlayerActivity>()
                                .single()
                    }
                    assertTrue("Source lookup must reach the controlled WebDAV boundary", server.sourceEntered.await(10, TimeUnit.SECONDS))
                    SystemClock.sleep(350)
                    var sourcePanelVisible = false
                    var panel: View? = null
                    instrumentation.runOnMainSync {
                        panel = activity.findViewById(com.xyoye.player_component.R.id.loading_panel)
                        sourcePanelVisible = panel?.isShown == true
                    }
                    screenshot("${kernel.name}-source")
                    if (cancel) {
                        val automation = instrumentation.uiAutomation
                        repeat(3) {
                            val time = SystemClock.uptimeMillis()
                            automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), false)
                            automation.injectInputEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), false)
                            SystemClock.sleep(120)
                        }
                        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
                        server.sourceReady.countDown()
                        assertFalse(
                            "Cancelled preparation must not start the kernel later",
                            server.kernelEntered.await(500, TimeUnit.MILLISECONDS)
                        )
                        return@use
                    }
                    var missingFrames = 0
                    var observedFrames = 0
                    val observer =
                        ViewTreeObserver.OnPreDrawListener {
                            observedFrames++
                            if (panel?.isShown != true) missingFrames++
                            true
                        }
                    instrumentation.runOnMainSync {
                        activity.window.decorView.viewTreeObserver
                            .addOnPreDrawListener(observer)
                    }
                    server.sourceReady.countDown()
                    assertTrue("The real kernel must request video bytes", server.kernelEntered.await(15, TimeUnit.SECONDS))
                    SystemClock.sleep(350)
                    instrumentation.runOnMainSync {
                        activity.window.decorView.viewTreeObserver
                            .removeOnPreDrawListener(observer)
                        assertTrue(
                            "The panel must stay mounted across both preparation stages",
                            panel === activity.findViewById<View>(com.xyoye.player_component.R.id.loading_panel)
                        )
                        assertTrue("Kernel preparation must remain visible", panel?.isShown == true)
                        assertEquals(
                            "正在准备播放…",
                            activity.findViewById<TextView>(com.xyoye.player_component.R.id.loading_text).text.toString()
                        )
                    }
                    screenshot("${kernel.name}-kernel")
                    assertTrue("Observe rendered frames across source-to-kernel handover", observedFrames > 0)
                    assertEquals("Preparation must not leave frames without feedback", 0, missingFrames)
                    server.kernelReady.countDown()
                    awaitPlaying(activity)
                    instrumentation.runOnMainSync {
                        assertTrue("Loading must finish after actual playback starts", panel?.isShown == false)
                        val actualKernel =
                            DanDanVideoPlayer::class.java
                                .getDeclaredField(
                                    "mVideoPlayer"
                                ).apply { isAccessible = true }
                                .get(player(activity))
                        assertEquals(
                            if (kernel ==
                                PlayerType.TYPE_MPV_PLAYER
                            ) {
                                "MpvVideoPlayer"
                            } else {
                                "Media3VideoPlayer"
                            },
                            actualKernel.javaClass.simpleName
                        )
                    }
                    assertTrue("Source and kernel preparation must use the same visible loading panel", sourcePanelVisible)
                }
        } finally {
            server.sourceReady.countDown()
            server.kernelReady.countDown()
            storage.close()
            server.stop()
            PlayerConfig.putUsePlayerType(originalKernel)
            runBlocking {
                val history = DatabaseProvider.instance.getPlayHistoryDao()
                history.getAll().filter { it.storageId == library.id }.forEach { history.delete(it.id) }
                dao.delete(library.url, library.mediaType)
            }
        }
    }

    private fun awaitPlaying(activity: PlayerActivity) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = player(activity).isPlaying() && player(activity).getDuration() > 0 }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("The requested kernel did not play the real video")
    }

    private fun player(activity: PlayerActivity) =
        activity.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val output = File(context.getExternalFilesDir(null), "ux01/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class GatedVideoServer(
        private val video: File
    ) : NanoHTTPD("127.0.0.1", 0) {
        val sourceEntered = CountDownLatch(1)
        val sourceReady = CountDownLatch(1)
        val kernelEntered = CountDownLatch(1)
        val kernelReady = CountDownLatch(1)
        val baseUrl get() = "http://127.0.0.1:$listeningPort"

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/loading.mp4") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Missing")
            if (session.method.name == "PROPFIND") {
                sourceEntered.countDown()
                check(sourceReady.await(30, TimeUnit.SECONDS))
                val xml =
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:"><d:response><d:href>$baseUrl/loading.mp4</d:href>
                    <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${video.length()}</d:getcontentlength>
                    <d:getcontenttype>video/mp4</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat></d:response></d:multistatus>
                    """.trimIndent()
                return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml", xml)
            }
            kernelEntered.countDown()
            check(kernelReady.await(30, TimeUnit.SECONDS))
            val offset =
                session.headers["range"]
                    ?.substringAfter("bytes=")
                    ?.substringBefore('-')
                    ?.toLongOrNull() ?: 0L
            val stream = video.inputStream().apply { channel.position(offset) }
            return newFixedLengthResponse(
                if (offset > 0) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                "video/mp4",
                stream,
                video.length() - offset
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                if (offset > 0) addHeader("Content-Range", "bytes $offset-${video.length() - 1}/${video.length()}")
            }
        }
    }
}
