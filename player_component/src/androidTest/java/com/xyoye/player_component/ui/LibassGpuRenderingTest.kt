package com.xyoye.player_component.ui

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.player.subtitle.backend.LibassEmbeddedSubtitleSink
import com.xyoye.player.subtitle.backend.SwitchableEmbeddedSubtitleSink
import com.xyoye.player.subtitle.gpu.AssGpuNativeBridge
import com.xyoye.player.subtitle.gpu.AssGpuRenderer
import com.xyoye.player.subtitle.gpu.LocalSubtitlePipelineApi
import com.xyoye.player.subtitle.gpu.SubtitlePipelineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibassGpuRenderingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val target =
        SubtitleOutputTarget(
            viewType = SubtitleViewType.SurfaceView,
            width = 640,
            height = 360
        )

    @Test
    fun surfaceDestroyedBeforeQueuedBind_reportsSurfaceLossWithoutEnteringEgl() {
        val thread = HandlerThread("LibassStaleSurfaceTest").apply { start() }
        val handler = Handler(thread.looper)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queueGate = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var reason: SubtitlePipelineFallbackReason? = null
        val renderer =
            AssGpuRenderer(
                SubtitlePipelineController(LocalSubtitlePipelineApi()),
                handler,
                scope = scope,
                pipelineErrorListener = { failure, _ ->
                    reason = failure
                    failed.countDown()
                }
            )
        val output = ImageReader.newInstance(target.width, target.height, PixelFormat.RGBA_8888, 3)
        try {
            handler.post { queueGate.await(5, TimeUnit.SECONDS) }
            renderer.bindSurface("stale", output.surface, target)
            output.close()
            queueGate.countDown()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            org.junit.Assert.assertEquals(SubtitlePipelineFallbackReason.SURFACE_LOST, reason)
        } finally {
            queueGate.countDown()
            renderer.release()
            thread.quitSafely()
            thread.join(1500)
            scope.cancel()
            output.close()
        }
    }

    @Test
    fun hidingRenderer_clearsPixelsAndPreservesTrackForPausedRestore() {
        val thread = HandlerThread("LibassVisibilityTest").apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val renderer =
            AssGpuRenderer(
                SubtitlePipelineController(LocalSubtitlePipelineApi()),
                Handler(thread.looper),
                scope = scope
            )
        val output = ImageReader.newInstance(target.width, target.height, PixelFormat.RGBA_8888, 3)
        try {
            renderer.bindSurface("test", output.surface, target)
            assertFalse(hasVisiblePixels(output))
            assertTrue(runBlocking { renderer.loadTrack(createTrack().absolutePath, emptyList(), DEFAULT_FONT) })
            assertFalse(hasVisiblePixels(output))
            renderer.renderFrame(1000, 1)
            assertTrue(hasVisiblePixels(output))
            renderer.setVisible(false)
            assertFalse(hasVisiblePixels(output))
            renderer.setVisible(true)
            renderer.renderFrame(1000, 2)
            assertTrue(hasVisiblePixels(output))
            renderer.clearEmbeddedTrack()
            assertFalse(hasVisiblePixels(output))

            val sink = SwitchableEmbeddedSubtitleSink(LibassEmbeddedSubtitleSink(renderer, emptyList(), DEFAULT_FONT))
            sink.onFormat(HEADER.toByteArray())
            assertFalse(hasVisiblePixels(output))
            sink.onSample("0,0,Default,,0,0,0,,Embedded".toByteArray(), 0, 10_000_000)
            renderer.renderFrame(1000, 3)
            assertTrue(hasVisiblePixels(output))
            sink.setEnabled(false)
            assertTrue(runBlocking { renderer.loadTrack(createTrack().absolutePath, emptyList(), DEFAULT_FONT) })
            assertFalse(hasVisiblePixels(output))
            sink.onRelease()
            renderer.renderFrame(1000, 4)
            assertTrue("releasing a muted decoder must retain external subtitles", hasVisiblePixels(output))

            renderer.clearEmbeddedTrack()
            assertFalse(hasVisiblePixels(output))
            sink.setEnabled(true)
            repeat(2) {
                sink.onFormat(HEADER.toByteArray())
                assertFalse(hasVisiblePixels(output))
                sink.onSample("0,0,Default,,0,0,0,,Reenabled".toByteArray(), 0, 10_000_000)
                renderer.renderFrame(1000, 5)
                assertTrue("the sink must remain reusable after decoder release", hasVisiblePixels(output))
                sink.onRelease()
                assertFalse(hasVisiblePixels(output))
            }
        } finally {
            renderer.release()
            thread.quitSafely()
            thread.join(1500)
            scope.cancel()
            output.close()
            File(context.cacheDir, "libass-valid.ass").delete()
        }
    }

    @Test
    fun clearAndSurfaceRecreation_redrawTheSamePausedTimestamp() =
        withRenderer { bridge, output ->
            assertTrue(bridge.loadTrack(createTrack().absolutePath, emptyList(), DEFAULT_FONT))
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1000, 1).rendered)
            assertTrue(hasVisiblePixels(output))

            bridge.flush()
            assertFalse("flush must submit a transparent frame", hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1000, 2).rendered)
            assertTrue("same timestamp must redraw after clearing", hasVisiblePixels(output))

            bridge.detachSurface()
            assertTrue(bridge.attachSurface(output.surface, target))
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1000, 3).rendered)
            assertTrue("new EGL surface must receive the cached ASS frame", hasVisiblePixels(output))

            bridge.clearEmbeddedTrack()
            assertFalse("clearing a track must remove its last frame", hasVisiblePixels(output))
            assertFalse(bridge.renderFrame(1000, 4).rendered)
        }

    @Test
    fun invalidFiles_reportFailureAndClearPreviousOutput() =
        withRenderer { bridge, output ->
            val valid = createTrack()
            val invalid = File(context.cacheDir, "libass-invalid.ass")
            for (contents in listOf("", "not an ASS subtitle")) {
                assertTrue(bridge.loadTrack(valid.absolutePath, emptyList(), DEFAULT_FONT))
                hasVisiblePixels(output)
                assertTrue(bridge.renderFrame(1000, 1).rendered)
                assertTrue(hasVisiblePixels(output))
                invalid.writeText(contents)
                assertFalse(bridge.loadTrack(invalid.absolutePath, emptyList(), DEFAULT_FONT))
                assertFalse(hasVisiblePixels(output))
                assertFalse(bridge.renderFrame(1000, 2).rendered)
            }
            invalid.delete()
            assertFalse(bridge.loadTrack(invalid.absolutePath, emptyList(), DEFAULT_FONT))
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.loadTrack(valid.absolutePath, emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            assertTrue(bridge.renderFrame(1000, 3).rendered)
            assertTrue(hasVisiblePixels(output))
        }

    @Test
    fun embeddedEvents_use64BitTimestampsAndCanBeFlushed() =
        withRenderer { bridge, output ->
            assertTrue(bridge.initEmbeddedTrack(HEADER.toByteArray(), emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            val timestamp = Int.MAX_VALUE.toLong() + 10_000L
            bridge.appendEmbeddedChunk("0,0,Default,,0,0,0,,Visible".toByteArray(), timestamp, 2000)
            assertTrue(bridge.renderFrame(timestamp + 100, 1).rendered)
            assertTrue("timestamps must not wrap to negative Int values", hasVisiblePixels(output))
            bridge.flushEmbeddedEvents()
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(timestamp + 100, 2).rendered)
            assertFalse(hasVisiblePixels(output))
        }

    private fun createTrack(): File =
        File(context.cacheDir, "libass-valid.ass").apply {
            writeText(HEADER + "Dialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,Visible\n")
        }

    private fun withRenderer(verify: (AssGpuNativeBridge, ImageReader) -> Unit) {
        val bridge = AssGpuNativeBridge()
        val output = ImageReader.newInstance(target.width, target.height, PixelFormat.RGBA_8888, 3)
        try {
            assertTrue(bridge.isReady)
            assertTrue(bridge.attachSurface(output.surface, target))
            assertFalse(hasVisiblePixels(output))
            verify(bridge, output)
        } finally {
            bridge.release()
            output.close()
            File(context.cacheDir, "libass-valid.ass").delete()
            File(context.cacheDir, "libass-invalid.ass").delete()
        }
    }

    private fun hasVisiblePixels(output: ImageReader): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = output.acquireLatestImage()
            if (image == null) {
                SystemClock.sleep(10)
                continue
            }
            image.use {
                val plane = it.planes[0]
                val pixels = plane.buffer
                for (y in 0 until it.height) {
                    for (x in 0 until it.width) {
                        if (pixels.get(y * plane.rowStride + x * plane.pixelStride + 3).toInt() != 0) return true
                    }
                }
                return false
            }
        }
        throw AssertionError("No subtitle surface frame was submitted")
    }

    private companion object {
        private const val DEFAULT_FONT = "/system/fonts/Roboto-Regular.ttf"
        private val HEADER =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Roboto,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """.trimIndent() + "\n"
    }
}
