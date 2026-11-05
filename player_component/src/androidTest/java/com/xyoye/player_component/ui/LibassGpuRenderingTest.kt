package com.xyoye.player_component.ui

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.player.kernel.impl.media3.LibassMatroskaExtractor
import com.xyoye.player.kernel.impl.media3.LibassSubtitleParserFactory
import com.xyoye.player.kernel.impl.media3.Media3AssSample
import com.xyoye.player.kernel.impl.media3.Media3FontAttachmentSink
import com.xyoye.player.kernel.impl.media3.Media3SubtitleInput
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LibassGpuRenderingTest {
    @Test
    fun compressedAndPlainMkv_generateTheSameActualAssPixels() =
        withRenderer { bridge, output ->
            var expected: Long? = null
            for (asset in listOf("subtitle-plain.mkv", "subtitle-zlib-explicit.mkv", "subtitle-zlib-default.mkv")) {
                val input = Media3SubtitleInput()
                val fonts = input.beginMedia()
                input.setSink(
                    object : EmbeddedSubtitleSink {
                        override fun onFormat(codecPrivate: ByteArray?) {
                            assertTrue(bridge.initEmbeddedTrack(codecPrivate, emptyList(), DEFAULT_FONT))
                            hasVisiblePixels(output)
                        }

                        override fun onSample(
                            data: ByteArray,
                            timeUs: Long,
                            durationUs: Long?
                        ) {
                            bridge.appendEmbeddedChunk(data, timeUs / 1000, durationUs?.div(1000))
                        }

                        override fun onFlush() = bridge.flushEmbeddedEvents()

                        override fun onRelease() = bridge.clearEmbeddedTrack()
                    }
                )
                val extractor = LibassMatroskaExtractor(fonts, input, LibassSubtitleParserFactory(input))
                val extracted = FakeExtractorOutput()
                extractor.init(extracted)
                val data =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .context.assets
                        .open("media3/$asset")
                        .use { it.readBytes() }
                val stream = FakeExtractorInput.Builder().setData(data).build()
                try {
                    while (extractor.read(stream, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
                    input.selectTrack(
                        extracted.trackOutputs
                            .valueAt(0)
                            .lastFormat!!
                            .id
                    )
                    assertTrue(bridge.renderFrame(500_000, null).imageCount > 0)
                    val pixels = pixelSignature(output)
                    assertTrue(pixels != 0L)
                    if (expected == null) expected = pixels else assertEquals(expected, pixels)
                    bridge.renderFrame(2_000_000, null)
                    assertFalse(hasVisiblePixels(output))
                } finally {
                    extractor.release()
                }
            }
        }

    @Test
    fun fullAssDocument_preservesNativeEventsAndAppliesTheContentWindowOffset() =
        withRenderer { bridge, output ->
            val document = (HEADER + "\nDialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\\pos(200,180)}External\n").toByteArray()
            assertTrue(bridge.readDocument(document, -2_000_000, emptyList(), DEFAULT_FONT))
            hasVisiblePixels(output)
            assertEquals(1, bridge.embeddedTrackInfo()!!.eventCount)
            bridge.renderFrame(500_000, null)
            assertFalse(hasVisiblePixels(output))
            assertTrue(bridge.renderFrame(1_500_000, null).imageCount > 0)
            assertTrue(hasVisiblePixels(output))
            bridge.renderFrame(2_000_000, null)
            assertFalse(hasVisiblePixels(output))
        }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val target =
        SubtitleOutputTarget(
            viewType = SubtitleViewType.SurfaceView,
            width = 640,
            height = 360
        )

    @Test
    fun decoderHeaders_renderChineseWithMissingEventsPaddingOrNoInitializationData() =
        withRenderer { bridge, output ->
            val header = HEADER.substringBefore("[Events]").toByteArray()
            val media3Format = MEDIA3_FORMAT.toByteArray()
            val text = "{\\b1}简体、繁體, visible"
            val raw = "0,0,Default,,0,0,0,,$text"
            val initializations =
                listOf(
                    listOf(media3Format, header),
                    listOf(media3Format, header + ByteArray(3)),
                    listOf(media3Format, HEADER.toByteArray() + ByteArray(3)),
                    listOf(HEADER.toByteArray()),
                    emptyList(),
                    listOf(ByteArray(0))
                )
            for (initialization in initializations) {
                val decoded = decodeSample(initialization, raw)
                assertTrue(bridge.initEmbeddedTrack(decoded.first, emptyList(), "/system/fonts/NotoSansCJK-Regular.ttc"))
                assertFalse(hasVisiblePixels(output))
                assertTrue(bridge.embeddedTrackInfo()!!.hasEventFormat)
                val parsed = bridge.appendEmbeddedChunk(decoded.second, 0, 10_000, collectDiagnostics = true)!!
                assertEquals(AssGpuNativeBridge.EmbeddedSampleOutcome.ACCEPTED, parsed.outcome)
                assertEquals(text.toByteArray().size, parsed.textBytes)
                val frame = bridge.renderFrame(1_000_000, null)
                assertTrue(frame.rendered)
                assertTrue(frame.imageCount > 0)
                assertTrue("decoded ASS must produce actual Chinese glyph pixels", hasVisiblePixels(output))
            }

            // Controlled regression: the old Media3-specific format loses the Text field in libass.
            val oldHeader = header + "\n[Events]\n$MEDIA3_FORMAT\n".toByteArray()
            assertTrue(bridge.initEmbeddedTrack(oldHeader, emptyList(), DEFAULT_FONT))
            assertFalse(hasVisiblePixels(output))
            val rejectedText = bridge.appendEmbeddedChunk("0,0,Default,,0,0,0,,Regression".toByteArray(), 0, 10_000, true)!!
            assertTrue(rejectedText.textBytes <= 0)
            assertTrue(bridge.renderFrame(1_000_000, null).rendered)
            assertFalse("the old malformed event format must be detected by pixel verification", hasVisiblePixels(output))
        }

    @Test
    fun parserDiagnostics_distinguishDuplicatesEmptyTextAndRejectedPacketsFromVisibleEvents() =
        withRenderer { bridge, output ->
            assertTrue(bridge.initEmbeddedTrack(HEADER.toByteArray(), emptyList(), DEFAULT_FONT))
            assertFalse(hasVisiblePixels(output))
            val visible = "0,0,Default,,0,0,0,,Private subtitle sentinel"
            val accepted = bridge.appendEmbeddedChunk(visible.toByteArray(), 0, 2000, true)!!
            assertEquals(AssGpuNativeBridge.EmbeddedSampleOutcome.ACCEPTED, accepted.outcome)
            assertEquals(1, accepted.addedEvents)
            assertTrue(accepted.track.styleCount > 0)

            val duplicate = bridge.appendEmbeddedChunk(visible.toByteArray(), 0, 2000, true)!!
            assertEquals(AssGpuNativeBridge.EmbeddedSampleOutcome.DUPLICATE, duplicate.outcome)
            assertEquals(0, duplicate.addedEvents)
            assertEquals(1, duplicate.track.eventCount)

            val empty = bridge.appendEmbeddedChunk("1,0,Default,,0,0,0,,".toByteArray(), 0, 2000, true)!!
            assertEquals(AssGpuNativeBridge.EmbeddedSampleOutcome.EMPTY_TEXT, empty.outcome)
            assertEquals(0, empty.textBytes)
            assertEquals(2, empty.track.eventCount)
            val rejected = bridge.appendEmbeddedChunk("broken".toByteArray(), 0, 2000, true)!!
            assertEquals(AssGpuNativeBridge.EmbeddedSampleOutcome.REJECTED, rejected.outcome)
            assertEquals(2, rejected.track.eventCount)

            assertTrue(bridge.renderFrame(1_000_000, null).imageCount > 0)
            assertTrue(hasVisiblePixels(output))
            bridge.setTelemetryEnabled(false)
            assertEquals(-1, bridge.renderFrame(1_000_000, null).imageCount)
            bridge.setTelemetryEnabled(true)
            assertEquals(-1, bridge.renderFrame(1_000_000, null, telemetryEnabled = false).imageCount)
            assertEquals(0, bridge.renderFrame(3_000_000, null).imageCount)
            assertFalse("outside event time must be a transparent frame, not a parser error", hasVisiblePixels(output))
            bridge.flushEmbeddedEvents()
            assertFalse(hasVisiblePixels(output))
            assertEquals(0, bridge.embeddedTrackInfo()!!.eventCount)
            assertEquals(
                AssGpuNativeBridge.EmbeddedSampleOutcome.ACCEPTED,
                bridge.appendEmbeddedChunk(visible.toByteArray(), 0, 2000, true)!!.outcome
            )
        }

    private fun decodeSample(
        initialization: List<ByteArray>,
        raw: String
    ): Pair<ByteArray?, ByteArray> {
        var header: ByteArray? = null
        val sample = AtomicReference<ByteArray>()
        val sink =
            object : EmbeddedSubtitleSink {
                override fun onFormat(codecPrivate: ByteArray?) {
                    header = codecPrivate
                }

                override fun onSample(
                    data: ByteArray,
                    timeUs: Long,
                    durationUs: Long?
                ) {
                    assertEquals(0L, timeUs)
                    assertEquals(10_000_000L, durationUs)
                    sample.set(data)
                }

                override fun onFlush() = Unit

                override fun onRelease() = Unit
            }
        val format =
            Format
                .Builder()
                .setSampleMimeType("text/x-ssa")
                .setInitializationData(initialization)
                .build()
        sink.onFormat(Media3AssSample.codecPrivate(format.initializationData))
        val (data, durationUs) = Media3AssSample.normalize("Dialogue: 0:00:00:00,0:00:10:00,$raw".toByteArray())
        sink.onSample(data, 0L, durationUs)
        return header to sample.get()
    }

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
        private const val MEDIA3_FORMAT = "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
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
