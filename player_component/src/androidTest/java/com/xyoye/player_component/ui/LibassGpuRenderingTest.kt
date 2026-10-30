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
import com.xyoye.player.kernel.impl.media3.Media3FontAttachmentSink
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun lateAttachment_changesActualGlyphsSurvivesTracksAndIsClearedWithMedia() =
        withRenderer { bridge, output ->
            val header = HEADER.replace("Style: Default,Roboto,", "Style: Default,Noto Serif,")
            val event = "0,0,Default,,0,0,0,,Visible"
            assertTrue(bridge.initEmbeddedTrack(header.toByteArray(), emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            bridge.appendEmbeddedChunk(event.toByteArray(), 0, 10_000)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            val fallback = pixelSignature(output)

            var registered = 0
            Media3FontFixture.extract(
                object : Media3FontAttachmentSink {
                    override fun reserve(size: Int): Boolean = size in 1..16 * 1024 * 1024

                    override fun cancel(size: Int) = Unit

                    override fun onFont(
                        name: String,
                        data: ByteArray
                    ) {
                        assertEquals("NotoSerif-Regular.ttf", name)
                        assertTrue(bridge.addFont(name, data))
                        registered++
                    }
                }
            )
            assertEquals("the real MKV extractor must emit the attachment", 1, registered)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            val attached = pixelSignature(output)
            assertNotEquals("late fonts must replace cached fallback glyphs", fallback, attached)

            bridge.clearEmbeddedTrack()
            hasVisiblePixels(output)
            assertTrue(bridge.initEmbeddedTrack(header.toByteArray(), emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            bridge.appendEmbeddedChunk(event.toByteArray(), 0, 10_000)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            assertEquals("same-media track changes must keep attachment fonts", attached, pixelSignature(output))

            bridge.resetMedia()
            hasVisiblePixels(output)
            assertTrue(bridge.initEmbeddedTrack(header.toByteArray(), emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            bridge.appendEmbeddedChunk(event.toByteArray(), 0, 10_000)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            assertEquals("new media must not inherit the previous font", fallback, pixelSignature(output))
        }

    @Test
    fun staleState_isRejectedBeforeSwapAndCurrentStateRedrawsSameTime() =
        withRenderer { bridge, output ->
            assertTrue(bridge.loadTrack(createTrack().absolutePath, emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            bridge.invalidateState(7L)
            assertFalse(bridge.renderFrame(1_000_000, System.nanoTime() + 1_000_000_000, stateRevision = 6).rendered)
            assertEquals(null, output.acquireLatestImage())
            assertTrue(bridge.renderFrame(1_000_000, null, stateRevision = 7).rendered)
            assertTrue(hasVisiblePixels(output))
            bridge.flush()
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1_000_000, null, stateRevision = 7).rendered)
            assertTrue(hasVisiblePixels(output))
        }

    @Test
    fun rotatedVideo_preservesScriptCoordinatesAndScreenVerticalOffset() =
        withRenderer { bridge, output ->
            assertTrue(bridge.initEmbeddedTrack(HEADER.toByteArray(), emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            bridge.appendEmbeddedChunk("0,0,Default,,0,0,0,,{\\an5\\pos(160,90)}X".toByteArray(), 0, 10_000)
            bridge.setVideoGeometry(720, 480, 1.185185f, 90)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            val initial = visibleBounds(output)
            assertTrue(
                "rotation must map script upper-left to output upper-right",
                initial[0] > target.width / 2 && initial[1] < target.height / 2
            )
            bridge.setVerticalOffsetPercent(10)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            val shifted = visibleBounds(output)
            assertTrue(kotlin.math.abs(initial[0] - shifted[0]) <= 1)
            assertTrue(kotlin.math.abs(initial[1] - shifted[1] - 36) <= 1)
        }

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
            renderer.renderFrame(1_000_000, 1)
            assertTrue(hasVisiblePixels(output))
            renderer.setVisible(false)
            assertFalse(hasVisiblePixels(output))
            renderer.setVisible(true)
            renderer.renderFrame(1_000_000, 2)
            assertTrue(hasVisiblePixels(output))
            renderer.clearEmbeddedTrack()
            assertFalse(hasVisiblePixels(output))

            val sink = SwitchableEmbeddedSubtitleSink(LibassEmbeddedSubtitleSink(renderer, emptyList(), DEFAULT_FONT))
            sink.onFormat(HEADER.toByteArray())
            assertFalse(hasVisiblePixels(output))
            sink.onSample("0,0,Default,,0,0,0,,Embedded".toByteArray(), 0, 10_000_000)
            renderer.renderFrame(1_000_000, 3)
            assertTrue(hasVisiblePixels(output))
            sink.setEnabled(false)
            assertTrue(runBlocking { renderer.loadTrack(createTrack().absolutePath, emptyList(), DEFAULT_FONT) })
            assertFalse(hasVisiblePixels(output))
            sink.onRelease()
            renderer.renderFrame(1_000_000, 4)
            assertTrue("releasing a muted decoder must retain external subtitles", hasVisiblePixels(output))

            renderer.clearEmbeddedTrack()
            assertFalse(hasVisiblePixels(output))
            sink.setEnabled(true)
            repeat(2) {
                sink.onFormat(HEADER.toByteArray())
                assertFalse(hasVisiblePixels(output))
                sink.onSample("0,0,Default,,0,0,0,,Reenabled".toByteArray(), 0, 10_000_000)
                renderer.renderFrame(1_000_000, 5)
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
            assertTrue(bridge.renderFrame(1_000_000, 1).rendered)
            assertTrue(hasVisiblePixels(output))

            bridge.flush()
            assertFalse("flush must submit a transparent frame", hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1_000_000, 2).rendered)
            assertTrue("same timestamp must redraw after clearing", hasVisiblePixels(output))

            bridge.detachSurface()
            assertTrue(bridge.attachSurface(output.surface, target))
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1_000_000, 3).rendered)
            assertTrue("new EGL surface must receive the cached ASS frame", hasVisiblePixels(output))

            bridge.clearEmbeddedTrack()
            assertFalse("clearing a track must remove its last frame", hasVisiblePixels(output))
            assertFalse(bridge.renderFrame(1_000_000, 4).rendered)
        }

    @Test
    fun invalidFiles_reportFailureAndClearPreviousOutput() =
        withRenderer { bridge, output ->
            val valid = createTrack()
            val invalid = File(context.cacheDir, "libass-invalid.ass")
            for (contents in listOf("", "not an ASS subtitle")) {
                assertTrue(bridge.loadTrack(valid.absolutePath, emptyList(), DEFAULT_FONT))
                hasVisiblePixels(output)
                assertTrue(bridge.renderFrame(1_000_000, 1).rendered)
                assertTrue(hasVisiblePixels(output))
                invalid.writeText(contents)
                assertFalse(bridge.loadTrack(invalid.absolutePath, emptyList(), DEFAULT_FONT))
                assertFalse(hasVisiblePixels(output))
                assertFalse(bridge.renderFrame(1_000_000, 2).rendered)
            }
            invalid.delete()
            assertFalse(bridge.loadTrack(invalid.absolutePath, emptyList(), DEFAULT_FONT))
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.loadTrack(valid.absolutePath, emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            assertTrue(bridge.renderFrame(1_000_000, 3).rendered)
            assertTrue(hasVisiblePixels(output))
        }

    @Test
    fun embeddedEvents_use64BitTimestampsAndCanBeFlushed() =
        withRenderer { bridge, output ->
            assertTrue(bridge.initEmbeddedTrack(HEADER.toByteArray(), emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            val timestamp = Int.MAX_VALUE.toLong() + 10_000L
            bridge.appendEmbeddedChunk("0,0,Default,,0,0,0,,Visible".toByteArray(), timestamp, 2000)
            assertTrue(bridge.renderFrame((timestamp + 100) * 1000L, 1).rendered)
            assertTrue("timestamps must not wrap to negative Int values", hasVisiblePixels(output))
            bridge.flushEmbeddedEvents()
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame((timestamp + 100) * 1000L, 2).rendered)
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

    private fun pixelSignature(output: ImageReader): Long {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = output.acquireLatestImage()
            if (image == null) {
                SystemClock.sleep(10)
                continue
            }
            return image.use {
                val plane = it.planes[0]
                var hash = 1L
                for (y in 0 until it.height) {
                    for (x in 0 until it.width) {
                        hash = hash * 31 + plane.buffer.get(y * plane.rowStride + x * plane.pixelStride + 3)
                    }
                }
                hash
            }
        }
        throw AssertionError("No frame for pixel comparison")
    }

    private fun visibleBounds(output: ImageReader): IntArray {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = output.acquireLatestImage()
            if (image == null) {
                SystemClock.sleep(10)
                continue
            }
            return image.use {
                val plane = it.planes[0]
                var minX = it.width
                var minY = it.height
                var maxX = -1
                var maxY = -1
                for (y in 0 until it.height) {
                    for (x in 0 until it.width) {
                        if (plane.buffer.get(y * plane.rowStride + x * plane.pixelStride + 3).toInt() != 0) {
                            minX = minOf(minX, x)
                            minY = minOf(minY, y)
                            maxX = maxOf(maxX, x)
                            maxY = maxOf(maxY, y)
                        }
                    }
                }
                assertTrue("render must contain actual visible glyphs", maxX >= 0)
                intArrayOf((minX + maxX) / 2, (minY + maxY) / 2)
            }
        }
        throw AssertionError("No frame for geometry comparison")
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
