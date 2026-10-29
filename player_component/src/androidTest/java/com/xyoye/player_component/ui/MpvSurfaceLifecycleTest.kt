package com.xyoye.player_component.ui

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.player.kernel.impl.mpv.MpvNativeBridge
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MpvSurfaceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nativeSubtitlePixelsSurviveVisibilitySeekAndBothSurfaceRecreation() {
        val fixture = File(instrumentation.targetContext.cacheDir, "mpv-ass-compatibility.mkv")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val bridge = MpvNativeBridge()
        val events = CopyOnWriteArrayList<MpvNativeBridge.Event>()
        val (width, height) = videoSize(fixture)
        var output = createOutput(width, height, AtomicInteger())
        val pixels = AtomicReference(PixelSnapshot())
        var osd = createSubtitleOutput(pixels)
        try {
            bridge.setEventListener { events.add(it) }
            initialize(bridge)
            attach(bridge, output.surface)
            attachSubtitles(bridge, osd.surface)
            bridge.setSubtitleFonts("/system/fonts", "Roboto")
            load(bridge, fixture)
            await("first native ASS pixels", events) { pixels.get().count > 50 }
            val sid = bridge.listTracks().first { it.type == com.xyoye.data_component.enums.TrackType.SUBTITLE }.id
            bridge.seek(2000)
            await("subtitle-free interval", events) { pixels.get().count == 0 }
            bridge.seek(3200)
            await("native ASS pixels after paused seek", events) { pixels.get().count > 50 }
            bridge.setSubtitleVisible(false)
            await("transparent hidden subtitle plane", events) { pixels.get().count == 0 }
            bridge.setSubtitleVisible(true)
            await("paused visibility restore", events) { pixels.get().count > 50 }
            bridge.setSubtitleDelay(10_000)
            await("subtitle delay clears old pixels", events) { pixels.get().count == 0 }
            bridge.setSubtitleDelay(0)
            await("subtitle delay restore", events) { pixels.get().count > 50 }
            val beforeMove = pixels.get()
            bridge.setSubtitlePosition(20)
            await("native subtitle moves upwards", events) { pixels.get().maxY in 0 until beforeMove.maxY - 20 }
            bridge.setSubtitlePosition(0)
            await("position reset", events) { pixels.get().maxY == beforeMove.maxY }
            assertTrue(bridge.deselectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE))
            await("track disable clears pixels", events) { pixels.get().count == 0 }
            assertTrue(bridge.selectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE, sid))
            await("track reselect redraws pixels", events) { pixels.get().count > 50 }

            val restartsBeforeReplacement = events.count { it is MpvNativeBridge.Event.RenderingStart }
            val positionBeforeReplacement = bridge.currentPosition()
            assertTrue("The fixture must be paused within its second subtitle", positionBeforeReplacement in 3000..3999)
            assertTrue(bridge.detachSubtitleSurface())
            assertTrue(bridge.detachSurface())
            osd.close()
            output.close()
            pixels.set(PixelSnapshot())
            osd = createSubtitleOutput(pixels)
            output = createOutput(width, height, AtomicInteger())
            attachSubtitles(bridge, osd.surface)
            attach(bridge, output.surface)
            await("paused ASS redraw on replacement pair", events) { pixels.get().count > 50 }
            await("settled playback restart after pair replacement", events) {
                events.count { it is MpvNativeBridge.Event.RenderingStart } > restartsBeforeReplacement
            }
            assertTrue(
                "Paused position must be preserved: before=$positionBeforeReplacement after=${bridge.currentPosition()}",
                kotlin.math.abs(bridge.currentPosition() - positionBeforeReplacement) < 250
            )
        } finally {
            bridge.detachSubtitleSurface()
            bridge.detachSurface()
            bridge.destroy()
            osd.close()
            output.close()
            fixture.delete()
        }
    }

    @Test
    fun externalNativeSubtitlesReportFailureAndCancelWithoutReplacingEmbeddedTrack() {
        val fixture = File(instrumentation.targetContext.cacheDir, "mpv-external-test.mkv")
        val srt = File(instrumentation.targetContext.cacheDir, "mpv-external.srt")
        val ass = File(instrumentation.targetContext.cacheDir, "mpv-external.ass")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        instrumentation.context.assets.open("media3/subtitle-compatibility.ass").use { input ->
            ass.outputStream().use { input.copyTo(it) }
        }
        srt.writeText("1\n00:00:00,000 --> 00:00:20,000\nExternal SRT\n")
        val bridge = MpvNativeBridge()
        val events = CopyOnWriteArrayList<MpvNativeBridge.Event>()
        val output = createOutput(64, 64, AtomicInteger())
        val pixels = AtomicReference(PixelSnapshot())
        val osd = createSubtitleOutput(pixels)
        try {
            bridge.setEventListener { events.add(it) }
            initialize(bridge)
            bridge.setSubtitleFonts("/system/fonts", "Roboto")
            attach(bridge, output.surface)
            attachSubtitles(bridge, osd.surface)
            load(bridge, fixture)
            await("embedded subtitle pixels", events) { pixels.get().count > 50 }
            val sid = bridge.listTracks().first { it.type == com.xyoye.data_component.enums.TrackType.SUBTITLE }.id
            bridge.seek(2000)
            await("empty interval before external subtitle", events) { pixels.get().count == 0 }
            assertTrue(runBlocking { bridge.loadExternalSubtitle(srt.absolutePath) })
            await("external SRT rendered by mpv", events) { pixels.get().count > 50 }
            val normalSize = pixels.get().count
            bridge.setSubtitleScale(100)
            await("native text scaling", events) { pixels.get().count > normalSize * 1.5 }
            bridge.setSubtitleScale(0)
            bridge.clearExternalSubtitle()
            await("external removal clears pixels", events) { pixels.get().count == 0 }
            assertFalse(runBlocking { bridge.loadExternalSubtitle(srt.absolutePath + ".missing") })
            val invalid = File(instrumentation.targetContext.cacheDir, "mpv-invalid.ass").apply { writeText("") }
            try {
                assertFalse(runBlocking { bridge.loadExternalSubtitle(invalid.absolutePath) })
            } finally {
                invalid.delete()
            }
            assertTrue("Failed loads must not leave external tracks", bridge.listTracks().none { it.external })

            assertTrue(runBlocking { bridge.loadExternalSubtitle(ass.absolutePath) })
            bridge.seek(3200)
            await("external ASS rendered by mpv", events) { pixels.get().count > 50 }
            assertTrue(bridge.selectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE, sid))
            bridge.clearExternalSubtitle()
            await("external cleanup preserves newly selected embedded track", events) {
                bridge.listTracks().any { it.id == sid && it.selected } && bridge.listTracks().none { it.external }
            }
            assertTrue(pixels.get().count > 50)
            val requested = CountDownLatch(1)
            val responseGate = CountDownLatch(1)
            val server =
                object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(request: IHTTPSession): Response {
                        requested.countDown()
                        responseGate.await(5, TimeUnit.SECONDS)
                        return newFixedLengthResponse(Response.Status.OK, "application/x-subrip", srt.readText())
                    }
                }
            try {
                server.start(5000, true)
                runBlocking {
                    val pending =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            bridge.loadExternalSubtitle("http://127.0.0.1:${server.listeningPort}/pending.srt")
                        }
                    assertTrue("Cancellation must happen during native subtitle IO", requested.await(5, TimeUnit.SECONDS))
                    pending.cancelAndJoin()
                    responseGate.countDown()
                }
                assertTrue(bridge.selectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE, sid))
                await("cancelled native IO leaves no external track", events) { bridge.listTracks().none { it.external } }
            } finally {
                responseGate.countDown()
                server.stop()
            }
            assertTrue(bridge.selectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE, sid))
            await("cancelled load leaves no native external track", events) { bridge.listTracks().none { it.external } }
            assertTrue(bridge.listTracks().any { it.id == sid && it.selected })
            assertTrue(events.none { it is MpvNativeBridge.Event.Error })
        } finally {
            bridge.detachSubtitleSurface()
            bridge.detachSurface()
            bridge.destroy()
            osd.close()
            output.close()
            fixture.delete()
            srt.delete()
            ass.delete()
        }
        assertFalse("Loads after release must complete instead of hanging", runBlocking { bridge.loadExternalSubtitle(srt.absolutePath) })
    }

    private data class PixelSnapshot(
        val count: Int = 0,
        val maxY: Int = -1
    )

    private fun createSubtitleOutput(pixels: AtomicReference<PixelSnapshot>): ImageReader =
        ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { frame ->
                    val plane = frame.planes[0]
                    var count = 0
                    var maxY = -1
                    for (y in 0 until frame.height) {
                        for (x in 0 until frame.width) {
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            // Ignore opaque black letterbox fill; require actual colored glyph pixels.
                            if ((plane.buffer.get(offset + 3).toInt() and 255) > 0 &&
                                (0..2).any { (plane.buffer.get(offset + it).toInt() and 255) > 20 }
                            ) {
                                count++
                                maxY = y
                            }
                        }
                    }
                    pixels.set(PixelSnapshot(count, maxY))
                }
            }, Handler(Looper.getMainLooper()))
        }

    private fun attachSubtitles(
        bridge: MpvNativeBridge,
        surface: Surface
    ) {
        bridge.setSubtitleSurfaceSize(640, 360)
        awaitResult(bridge) { bridge.setSubtitleSurface(surface, it) }
    }

    @Test
    fun httpHeaderValuesSurviveListEscapingAndAreClearedForNextSource() {
        val fixture = File(instrumentation.targetContext.cacheDir, "mpv-http-headers.mkv")
        instrumentation.context.assets.open("media3/decoder-lifecycle.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(request: IHTTPSession): Response {
                    requests.add(request.uri to request.headers["x-mpv-test"])
                    return newFixedLengthResponse(Response.Status.OK, "video/x-matroska", fixture.inputStream(), fixture.length())
                }
            }
        val bridge = MpvNativeBridge()
        val events = CopyOnWriteArrayList<MpvNativeBridge.Event>()
        val (width, height) = videoSize(fixture)
        val output = createOutput(width, height, AtomicInteger())
        val headerValue = "alpha,beta\\gamma"
        try {
            server.start(5000, true)
            bridge.setEventListener { events.add(it) }
            initialize(bridge)
            attach(bridge, output.surface)
            awaitResult(bridge) {
                bridge.setDataSource("http://127.0.0.1:${server.listeningPort}/first", mapOf("X-Mpv-Test" to headerValue), it)
            }
            bridge.play()
            await("first HTTP header", events) { requests.any { it.first == "/first" } }
            assertTrue("Unexpected test header: $requests", requests.filter { it.first == "/first" }.all { it.second == headerValue })
            awaitResult(bridge) {
                bridge.setDataSource("http://127.0.0.1:${server.listeningPort}/second", emptyMap(), it)
            }
            bridge.play()
            await("second HTTP request", events) { requests.any { it.first == "/second" } }
            assertTrue("Headers must not leak to the next source", requests.filter { it.first == "/second" }.all { it.second == null })
        } finally {
            bridge.detachSurface()
            bridge.destroy()
            server.stop()
            output.close()
            fixture.delete()
        }
    }

    @Test
    fun unsupportedVideoWithPlayableAudio_reportsErrorWithoutAudioOnlyPlayback() {
        val fixture = File(instrumentation.targetContext.cacheDir, "mpv-unsupported-video.mkv")
        val bytes =
            instrumentation.context.assets
                .open("media3/decoder-lifecycle.mkv")
                .use { it.readBytes() }
        val codec = "V_MPEG4/ISO/AVC"
        val codecOffset = bytes.toString(Charsets.ISO_8859_1).indexOf(codec)
        assertTrue("The fixture must contain an AVC CodecID", codecOffset >= 0)
        val unsupportedCodec = "V_NOT_SUPPORTED".toByteArray(Charsets.US_ASCII)
        assertEquals("Replacing the CodecID must preserve EBML element sizes", codec.length, unsupportedCodec.size)
        unsupportedCodec.copyInto(bytes, codecOffset)
        fixture.writeBytes(bytes)
        val bridge = MpvNativeBridge()
        val events = CopyOnWriteArrayList<MpvNativeBridge.Event>()
        var output: ImageReader? = null
        try {
            run {
                initialize(bridge)
                bridge.setEventListener { events.add(it) }
                bridge.applyDefaultOptions(LogLevel.INFO)
                bridge.setVolume(0f)
                output = createOutput(640, 360, AtomicInteger())
                attach(bridge, output!!.surface)
                load(bridge, fixture)
                bridge.play()
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (events.none { it is MpvNativeBridge.Event.Error } && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            assertTrue("Unsupported video must reach the native error callback: $events", events.any { it is MpvNativeBridge.Event.Error })
            assertTrue(
                "Failed video must not be reported as audio-only playback: $events",
                events.none { it is MpvNativeBridge.Event.RenderingStart }
            )
        } finally {
            run {
                bridge.detachSurface()
                bridge.destroy()
                output?.setOnImageAvailableListener(null, null)
                output?.close()
            }
            fixture.delete()
        }
    }

    @Test
    fun nativePlayback_detachAndReplaceSurface_resumesFramesAndReleasesCleanly() {
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "mpv-surface-lifecycle.mkv")
        instrumentation.context.assets.open("media3/decoder-lifecycle.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val bridge = MpvNativeBridge()
        val events = CopyOnWriteArrayList<MpvNativeBridge.Event>()
        val firstFrames = AtomicInteger()
        val replacementFrames = AtomicInteger()
        val positionTrace = CopyOnWriteArrayList<String>()
        var firstOutput: ImageReader? = null
        var replacementOutput: ImageReader? = null
        var pausedPosition = 0L
        try {
            val (width, height) = videoSize(fixture)
            run {
                initialize(bridge)
                bridge.setEventListener {
                    events.add(it)
                    if (it is MpvNativeBridge.Event.RenderingStart) {
                        positionTrace.add("restart=${bridge.currentPosition()}")
                    }
                }
                bridge.applyDefaultOptions(LogLevel.INFO)
                bridge.setVolume(0f)
                bridge.setLooping(true)
                firstOutput = createOutput(width, height, firstFrames)
                attach(bridge, firstOutput!!.surface)
                load(bridge, fixture)
                bridge.play()
            }
            await("initial decoded frame", events) { firstFrames.get() > 0 }
            assertMediaCodecOutput(bridge, events)
            await("initial playback restart", events) { events.any { it is MpvNativeBridge.Event.RenderingStart } }
            val restartsBeforeSeek = events.count { it is MpvNativeBridge.Event.RenderingStart }
            run {
                bridge.pause()
                bridge.seek(1000)
            }
            await("settled nonzero seek", events) {
                events.count { it is MpvNativeBridge.Event.RenderingStart } > restartsBeforeSeek
            }
            val restartsBeforeReplacement = events.count { it is MpvNativeBridge.Event.RenderingStart }

            run {
                pausedPosition = bridge.currentPosition()
                assertTrue("The paused Surface regression must begin at a nonzero position: $pausedPosition", pausedPosition >= 750)
                positionTrace.add("paused=$pausedPosition")
                assertTrue("Surface detach failed: ${bridge.lastError()}", bridge.detachSurface())
                positionTrace.add("detached=${bridge.currentPosition()}")
                firstOutput!!.setOnImageAvailableListener(null, null)
                firstOutput!!.close()
                firstOutput = null
                replacementOutput = createOutput(width, height, replacementFrames)
                attach(bridge, replacementOutput!!.surface)
                positionTrace.add("attached=${bridge.currentPosition()}")
            }
            await("paused frame on replacement Surface", events) { replacementFrames.get() > 0 }
            await("settled paused Surface restoration", events) {
                events.count { it is MpvNativeBridge.Event.RenderingStart } > restartsBeforeReplacement
            }
            assertMediaCodecOutput(bridge, events)
            run {
                val replacementPosition = bridge.currentPosition()
                positionTrace.add("replacementFrame=$replacementPosition")
                Log.i("MpvSurfaceLifecycleTest", positionTrace.joinToString())
                assertTrue(
                    "Surface replacement must preserve the paused position: $positionTrace",
                    kotlin.math.abs(replacementPosition - pausedPosition) < 250
                )
                bridge.play()
            }
            val framesBeforeResume = replacementFrames.get()
            await("new frames after resuming", events) { replacementFrames.get() > framesBeforeResume }

            val preparedBeforeReload = events.count { it is MpvNativeBridge.Event.Prepared }
            val framesBeforeReload = replacementFrames.get()
            run {
                load(bridge, fixture)
                bridge.play()
            }
            await("decoded frames after replacing the source", events) {
                events.count { it is MpvNativeBridge.Event.Prepared } > preparedBeforeReload && replacementFrames.get() > framesBeforeReload
            }
            assertMediaCodecOutput(bridge, events)

            run {
                bridge.stop()
                assertTrue("Final Surface detach failed: ${bridge.lastError()}", bridge.detachSurface())
                bridge.destroy()
                assertFalse("A released core must be unavailable", bridge.isAvailable)
            }
            instrumentation.waitForIdleSync()
            val eventCountAfterRelease = events.size
            SystemClock.sleep(100)
            instrumentation.waitForIdleSync()
            assertEquals("Native events must stop after release", eventCountAfterRelease, events.size)
            assertTrue("Unexpected mpv error: $events", events.none { it is MpvNativeBridge.Event.Error })
        } finally {
            run {
                bridge.detachSurface()
                bridge.destroy()
                firstOutput?.setOnImageAvailableListener(null, null)
                firstOutput?.close()
                replacementOutput?.setOnImageAvailableListener(null, null)
                replacementOutput?.close()
            }
            fixture.delete()
        }
    }

    private fun initialize(bridge: MpvNativeBridge) {
        awaitResult(bridge) { callback ->
            instrumentation.runOnMainSync { bridge.initialize(instrumentation.targetContext, callback) }
        }
        assertTrue("Real Plezy JNI must be available: ${bridge.lastError()}", bridge.isAvailable)
    }

    private fun attach(
        bridge: MpvNativeBridge,
        surface: Surface
    ) {
        awaitResult(bridge) { callback ->
            instrumentation.runOnMainSync { bridge.setSurface(surface, callback) }
        }
    }

    private fun load(
        bridge: MpvNativeBridge,
        fixture: File
    ) {
        awaitResult(bridge) { callback ->
            instrumentation.runOnMainSync { bridge.setDataSource(fixture.absolutePath, emptyMap(), callback) }
        }
    }

    private fun awaitResult(
        bridge: MpvNativeBridge,
        operation: ((Boolean) -> Unit) -> Unit
    ) {
        val completed = CountDownLatch(1)
        var success = false
        operation {
            assertEquals("Native result must return on the main thread", Looper.getMainLooper(), Looper.myLooper())
            success = it
            completed.countDown()
        }
        assertTrue("Native operation timed out: ${bridge.lastError()}", completed.await(10, TimeUnit.SECONDS))
        assertTrue("Native operation failed: ${bridge.lastError()}", success)
    }

    private fun createOutput(
        width: Int,
        height: Int,
        frames: AtomicInteger
    ): ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 3).apply {
            setOnImageAvailableListener(
                { reader ->
                    reader.acquireLatestImage()?.use { frames.incrementAndGet() }
                },
                Handler(Looper.getMainLooper())
            )
        }

    private fun videoSize(fixture: File): Pair<Int, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fixture.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    return format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
                }
            }
            error("The mpv lifecycle fixture must contain video")
        } finally {
            extractor.release()
        }
    }

    private fun assertMediaCodecOutput(
        bridge: MpvNativeBridge,
        events: List<MpvNativeBridge.Event>
    ) {
        await("observed MediaCodec decoder", events) { bridge.hwdecCurrent() == "mediacodec" }
        await("mediacodec output log", events) {
            events.any { it is MpvNativeBridge.Event.LogMessage && it.message?.contains("VO: [mediacodec]") == true }
        }
    }

    private fun await(
        description: String,
        events: List<MpvNativeBridge.Event>,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            assertTrue("mpv failed while awaiting $description: $events", events.none { it is MpvNativeBridge.Event.Error })
            SystemClock.sleep(25)
        }
        assertTrue("Expected $description within 10 seconds: $events", condition())
    }
}
