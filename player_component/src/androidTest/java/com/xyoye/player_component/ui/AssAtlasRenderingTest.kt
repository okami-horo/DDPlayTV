package com.xyoye.player_component.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.player.subtitle.gpu.AssGpuNativeBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class AssAtlasRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun benchmarkRenderingPaths() {
        val results = JSONArray()
        for (gles in listOf(2, 3)) {
            for (atlas in listOf(false, true)) {
                for ((name, document) in listOf("dialogue" to dialogue(), "karaoke" to karaoke(), "overlap" to rectangles(180))) {
                    withRenderer(atlas, gles) { bridge, output ->
                        assertTrue(bridge.readDocument(document.toByteArray(), 0, emptyList(), FONT))
                        pixels(output)
                        val samples = JSONArray()
                        repeat(180) { frame ->
                            val time = frame * 16_667L
                            val start = System.nanoTime()
                            val result = bridge.renderFrame(time, null)
                            val wallUs = (System.nanoTime() - start) / 1000
                            assertTrue(result.rendered)
                            if (result.submitted) discard(output)
                            if (frame >= 30) {
                                samples.put(
                                    JSONObject()
                                        .put("wallUs", wallUs)
                                        .put("layoutMs", result.renderLatencyMs)
                                        .put("packMs", result.packLatencyMs)
                                        .put("uploadMs", result.uploadLatencyMs)
                                        .put("drawSwapMs", result.compositeLatencyMs)
                                        .put("images", result.imageCount)
                                        .put("draws", result.drawCalls)
                                        .put("binds", result.textureBinds)
                                        .put("uploadedBytes", result.uploadedBytes)
                                        .put("textureBytes", result.textureBytes)
                                        .put("stagingBytes", result.stagingBytes)
                                        .put("pages", result.atlasPages)
                                        .put("submitted", result.submitted)
                                        .put("gles", result.glesVersion)
                                )
                            }
                        }
                        results.put(
                            JSONObject()
                                .put("case", name)
                                .put("atlas", atlas)
                                .put("gles", gles)
                                .put("frames", samples)
                        )
                    }
                }
            }
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "atlas-benchmark.json").writeText(results.toString(2))
    }

    @Test
    fun atlasMatchesReferencePixelsAndBatchesInPainterOrder() {
        for (gles in listOf(2, 3)) {
            for (document in listOf(dialogue(), karaoke(), rectangles(30))) {
                val reference = renderPixels(document, false, gles)
                val atlas = renderPixels(document, true, gles)
                assertPixelsEqual(reference.first, atlas.first)
                assertTrue("fixture must exercise several images", reference.second.drawCalls > 1)
                assertTrue("Atlas must batch image draws", atlas.second.drawCalls < reference.second.drawCalls)
                assertEquals(atlas.second.drawCalls, atlas.second.textureBinds)
            }
        }
    }

    @Test
    fun multiplePagesGrowthSameTimeRedrawAndFallback_preserveEveryPixel() {
        val document = rectangles(6)
        for (gles in listOf(2, 3)) {
            val reference = renderPixels(document, false, gles).first
            withRenderer(true, gles, 100) { bridge, output ->
                assertTrue(bridge.readDocument(document.toByteArray(), 0, emptyList(), FONT))
                pixels(output)
                val result = bridge.renderFrame(500_000, null)
                assertTrue("fixture must spill into multiple pages: $result", result.atlasPages > 1)
                assertPixelsEqual(reference, pixels(output))
                assertFalse(bridge.renderFrame(500_000, null).submitted)
                bridge.setGlobalOpacity(65)
                assertTrue(bridge.renderFrame(500_000, null).submitted)
                val translucent = pixels(output)
                assertPixelsEqual(renderPixels(document, false, gles, opacity = 65).first, translucent)
                bridge.detachSurface()
                assertTrue(bridge.attachSurface(output.surface, target))
                pixels(output)
                assertTrue(bridge.renderFrame(500_000, null).submitted)
                assertPixelsEqual(translucent, pixels(output))
                bridge.resetMedia()
                assertTrue(pixels(output).all { Color.alpha(it) == 0 })
            }
            val fallback = renderPixels(document, true, gles, pageSize = 8)
            assertEquals(0, fallback.second.atlasPages)
            assertPixelsEqual(reference, fallback.first)
        }
    }

    @Test
    fun repeatedSeekAndSizeChanges_releaseResourcesOnMediaReset() {
        val peaks = JSONArray()
        for (gles in listOf(2, 3)) {
            withRenderer(true, gles) { bridge, output ->
                assertTrue(bridge.readDocument(karaoke().toByteArray(), 0, emptyList(), FONT))
                pixels(output)
                var peakTextures = 0L
                var peakStaging = 0L
                repeat(3) { cycle ->
                    repeat(1800) { frame ->
                        if (frame % 30 == 0) bridge.setFontScale(if (frame % 60 == 0) 1.5f else 1f)
                        val result = bridge.renderFrame((frame % 180) * 16_667L, null)
                        assertTrue(result.rendered)
                        if (result.submitted) discard(output)
                        assertTrue(result.textureBytes <= 64 * 1024 * 1024)
                        assertTrue(result.stagingBytes <= 8 * 1024 * 1024)
                        peakTextures = maxOf(peakTextures, result.textureBytes)
                        peakStaging = maxOf(peakStaging, result.stagingBytes)
                        SystemClock.sleep(16)
                    }
                    peaks.put(
                        JSONObject()
                            .put("gles", gles)
                            .put("cycle", cycle)
                            .put("texturePeak", peakTextures)
                            .put("stagingPeak", peakStaging)
                            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                            .put("processPssKb", Debug.getPss())
                    )
                }
                bridge.resetMedia()
                pixels(output)
                val empty = bridge.renderFrame(0, null)
                assertEquals(0L, empty.textureBytes)
                assertEquals(0L, empty.stagingBytes)
            }
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "atlas-resource-cycles.json").writeText(peaks.toString(2))
    }

    @Test
    fun rotationPixelAspectOffsetAndScaling_matchTheReference() {
        for (gles in listOf(2, 3)) {
            for (rotation in listOf(0, 90, 180, 270)) {
                fun draw(atlas: Boolean): IntArray {
                    var result = intArrayOf()
                    withRenderer(atlas, gles) { bridge, output ->
                        bridge.setVideoGeometry(720, 480, 1.5f, rotation)
                        assertTrue(bridge.readDocument(dialogue().toByteArray(), 0, emptyList(), FONT))
                        pixels(output)
                        bridge.setVerticalOffsetPercent(10)
                        bridge.setFontScale(1.3f)
                        assertTrue(bridge.renderFrame(500_000, null).rendered)
                        result = pixels(output)
                    }
                    return result
                }
                assertPixelsEqual(draw(false), draw(true))
            }
        }
    }

    @Test
    fun imageBudgetRejectsWholeFrame_andAValidTrackCanRecover() {
        withRenderer(true, 3) { bridge, output ->
            assertTrue(bridge.readDocument(rectangles(4100).toByteArray(), 0, emptyList(), FONT))
            pixels(output)
            assertFalse(bridge.renderFrame(500_000, null).rendered)
            assertTrue(pixels(output).all { Color.alpha(it) == 0 })
            assertTrue(bridge.readDocument(dialogue().toByteArray(), 0, emptyList(), FONT))
            pixels(output)
            assertTrue(bridge.renderFrame(500_000, null).rendered)
            assertTrue(pixels(output).any { Color.alpha(it) > 0 })
        }
    }

    @Test
    fun actualVideoPlayback_recordsDecoderDropsForBothPaths() {
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "atlas-playback.mkv")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { source ->
            video.outputStream().use { source.copyTo(it) }
        }
        val results = JSONArray()
        try {
            for (gles in listOf(2, 3)) {
                for (atlas in listOf(false, true)) {
                    withRenderer(atlas, gles) { bridge, output ->
                        assertTrue(bridge.readDocument(rectangles(180).toByteArray(), 0, emptyList(), FONT))
                        pixels(output)
                        val videoOutput = ImageReader.newInstance(640, 360, ImageFormat.PRIVATE, 3)
                        videoOutput.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                        val time = AtomicLong(-1)
                        var player: ExoPlayer? = null
                        try {
                            instrumentation.runOnMainSync {
                                player =
                                    ExoPlayer.Builder(context).build().apply {
                                        trackSelectionParameters =
                                            trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                .build()
                                        setVideoSurface(videoOutput.surface)
                                        setVideoFrameMetadataListener { presentationTimeUs, _, _, _ -> time.set(presentationTimeUs) }
                                        repeatMode = Player.REPEAT_MODE_ONE
                                        setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
                                        prepare()
                                        play()
                                    }
                            }
                            val deadline = SystemClock.elapsedRealtime() + 8000
                            var last = -1L
                            var subtitleFrames = 0
                            while (SystemClock.elapsedRealtime() < deadline) {
                                val current = time.get()
                                if (current >= 0 && current != last) {
                                    val frame = bridge.renderFrame(current % 3_000_000, null)
                                    assertTrue(frame.rendered)
                                    if (frame.submitted) discard(output)
                                    subtitleFrames++
                                    last = current
                                } else {
                                    SystemClock.sleep(1)
                                }
                            }
                            instrumentation.runOnMainSync {
                                assertEquals(null, player!!.playerError)
                                val counters = player!!.videoDecoderCounters!!
                                counters.ensureUpdated()
                                assertTrue("Real video frames must be decoded", counters.renderedOutputBufferCount > 10)
                                assertTrue("Video metadata must drive subtitles", subtitleFrames > 10)
                                results.put(
                                    JSONObject()
                                        .put(
                                            "gles",
                                            gles
                                        ).put("atlas", atlas)
                                        .put("videoFrames", counters.renderedOutputBufferCount)
                                        .put("droppedVideoFrames", counters.droppedBufferCount)
                                        .put("subtitleFrames", subtitleFrames)
                                )
                            }
                        } finally {
                            instrumentation.runOnMainSync { player?.release() }
                            videoOutput.close()
                        }
                    }
                }
            }
        } finally {
            video.delete()
        }
        File(context.getExternalFilesDir(null), "atlas-video-drops.json").writeText(results.toString(2))
    }

    private fun renderPixels(
        document: String,
        atlas: Boolean,
        gles: Int,
        pageSize: Int = 0,
        opacity: Int = 100
    ): Pair<IntArray, AssGpuNativeBridge.NativeRenderResult> {
        var rendered: Pair<IntArray, AssGpuNativeBridge.NativeRenderResult>? = null
        withRenderer(atlas, gles, pageSize) { bridge, output ->
            assertTrue(bridge.readDocument(document.toByteArray(), 0, emptyList(), FONT))
            pixels(output)
            bridge.setGlobalOpacity(opacity)
            val result = bridge.renderFrame(500_000, null)
            assertEquals(gles, result.glesVersion)
            assertTrue(result.submitted)
            val colors = pixels(output)
            val bitmap = Bitmap.createBitmap(colors, target.width, target.height, Bitmap.Config.ARGB_8888)
            try {
                val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "atlas-pixels").apply { mkdirs() }
                File(directory, "${document.hashCode()}-$gles-$atlas-$pageSize-$opacity.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
            rendered = colors to result
        }
        return rendered!!
    }

    private fun assertPixelsEqual(
        expected: IntArray,
        actual: IntArray
    ) {
        assertEquals(expected.size, actual.size)
        var maxDifference = 0
        var changed = 0
        for (index in expected.indices) {
            if (expected[index] != actual[index]) changed++
            for (shift in 0..24 step 8) {
                maxDifference =
                    maxOf(maxDifference, kotlin.math.abs((expected[index] ushr shift and 255) - (actual[index] ushr shift and 255)))
            }
        }
        assertTrue("channel difference=$maxDifference changedPixels=$changed", maxDifference <= 2)
    }

    private fun withRenderer(
        atlas: Boolean,
        gles: Int,
        pageSize: Int = 0,
        verify: (AssGpuNativeBridge, ImageReader) -> Unit
    ) {
        val bridge = AssGpuNativeBridge()
        val output = ImageReader.newInstance(target.width, target.height, PixelFormat.RGBA_8888, 3)
        try {
            assertTrue(bridge.configureForTesting(atlas, pageSize, gles))
            assertTrue(bridge.attachSurface(output.surface, target))
            pixels(output)
            verify(bridge, output)
        } finally {
            bridge.release()
            output.close()
        }
    }

    private fun pixels(output: ImageReader): IntArray {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = output.acquireLatestImage()
            if (image == null) {
                SystemClock.sleep(1)
                continue
            }
            return image.use {
                val plane = it.planes[0]
                IntArray(it.width * it.height) { index ->
                    val offset = (index / it.width) * plane.rowStride + (index % it.width) * plane.pixelStride
                    Color.argb(
                        plane.buffer.get(offset + 3).toInt() and 255,
                        plane.buffer.get(offset).toInt() and 255,
                        plane.buffer.get(offset + 1).toInt() and 255,
                        plane.buffer.get(offset + 2).toInt() and 255
                    )
                }
            }
        }
        throw AssertionError("No submitted frame")
    }

    private fun discard(output: ImageReader) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = output.acquireLatestImage()
            if (image != null) {
                image.close()
                return
            }
            SystemClock.sleep(1)
        }
        throw AssertionError("No submitted benchmark frame")
    }

    private fun dialogue(): String =
        HEADER + "Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,{\\move(80,200,360,240,0,3000)\\blur2}Atlas 字幕 Subtitle\n"

    private fun karaoke(): String =
        HEADER +
            "Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,{\\pos(320,180)\\blur1\\t(0,3000,\\fscx140)}{\\kf50}Atlas {\\kf50}字幕 {\\kf50}Karaoke\n"

    private fun rectangles(count: Int): String =
        HEADER +
            (0 until count).joinToString("") { index ->
                val color = listOf("2020FF", "20FF20", "FF2020")[index % 3]
                "Dialogue: $index,0:00:00.00,0:01:00.00,Default,,0,0,0,,{\\an7\\move(${180 + index % 7},${120 + index % 5},${300 + index % 7},${160 + index % 5},0,3000)\\p1\\bord0\\shad0\\1c&H$color&\\alpha&H80&}m 0 0 l 60 0 60 36 0 36\n"
            }

    private companion object {
        private const val FONT = "/system/fonts/NotoSansCJK-Regular.ttc"
        private val target = SubtitleOutputTarget(viewType = SubtitleViewType.SurfaceView, width = 640, height = 360)
        private val HEADER =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Noto Sans CJK SC,36,&H00FFFFFF,&H000000FF,&H40000000,&H80000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """.trimIndent() + "\n"
    }
}
