package com.xyoye.player_component.ui

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.player.kernel.impl.mpv.MpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MpvSurfaceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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
            assertTrue("Real mpv JNI must be available: ${bridge.availabilityReason}", bridge.isAvailable)
            instrumentation.runOnMainSync {
                MpvNativeBridge.registerAndroidAppContext(instrumentation.targetContext)
                bridge.setEventListener { events.add(it) }
                assertTrue("mpv core initialization failed: ${bridge.lastError()}", bridge.ensureCreated())
                bridge.applyDefaultOptions(LogLevel.INFO)
                bridge.setVolume(0f)
                output = createOutput(640, 360, AtomicInteger())
                assertTrue("Surface bind failed: ${bridge.lastError()}", bridge.setSurface(output!!.surface))
                assertTrue("Fixture load failed: ${bridge.lastError()}", bridge.setDataSource(fixture.absolutePath, emptyMap()))
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
            instrumentation.runOnMainSync {
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
            assertTrue("Real mpv JNI must be available: ${bridge.availabilityReason}", bridge.isAvailable)
            val (width, height) = videoSize(fixture)
            instrumentation.runOnMainSync {
                MpvNativeBridge.registerAndroidAppContext(context)
                bridge.setEventListener {
                    events.add(it)
                    if (it is MpvNativeBridge.Event.RenderingStart) {
                        positionTrace.add("restart=${bridge.currentPosition()}")
                    }
                }
                assertTrue("mpv core initialization failed: ${bridge.lastError()}", bridge.ensureCreated())
                bridge.applyDefaultOptions(LogLevel.INFO)
                bridge.setVolume(0f)
                bridge.setLooping(true)
                firstOutput = createOutput(width, height, firstFrames)
                assertTrue("Initial Surface bind failed: ${bridge.lastError()}", bridge.setSurface(firstOutput!!.surface))
                assertTrue("Fixture load failed: ${bridge.lastError()}", bridge.setDataSource(fixture.absolutePath, emptyMap()))
                bridge.play()
            }
            await("initial decoded frame", events) { firstFrames.get() > 0 }
            assertMediaCodecOutput(bridge, events)
            await("initial playback restart", events) { events.any { it is MpvNativeBridge.Event.RenderingStart } }
            val restartsBeforeSeek = events.count { it is MpvNativeBridge.Event.RenderingStart }
            instrumentation.runOnMainSync {
                bridge.pause()
                bridge.seek(1000)
            }
            await("settled nonzero seek", events) {
                events.count { it is MpvNativeBridge.Event.RenderingStart } > restartsBeforeSeek
            }
            val restartsBeforeReplacement = events.count { it is MpvNativeBridge.Event.RenderingStart }

            instrumentation.runOnMainSync {
                pausedPosition = bridge.currentPosition()
                assertTrue("The paused Surface regression must begin at a nonzero position: $pausedPosition", pausedPosition >= 750)
                positionTrace.add("paused=$pausedPosition")
                assertTrue("Surface detach failed: ${bridge.lastError()}", bridge.setSurface(null))
                positionTrace.add("detached=${bridge.currentPosition()}")
                firstOutput!!.setOnImageAvailableListener(null, null)
                firstOutput!!.close()
                firstOutput = null
                replacementOutput = createOutput(width, height, replacementFrames)
                assertTrue("Replacement Surface bind failed: ${bridge.lastError()}", bridge.setSurface(replacementOutput!!.surface))
                positionTrace.add("attached=${bridge.currentPosition()}")
            }
            await("paused frame on replacement Surface", events) { replacementFrames.get() > 0 }
            await("settled paused Surface restoration", events) {
                events.count { it is MpvNativeBridge.Event.RenderingStart } > restartsBeforeReplacement
            }
            assertMediaCodecOutput(bridge, events)
            instrumentation.runOnMainSync {
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
            instrumentation.runOnMainSync {
                assertTrue("Fixture reload failed: ${bridge.lastError()}", bridge.setDataSource(fixture.absolutePath, emptyMap()))
                bridge.play()
            }
            await("decoded frames after replacing the source", events) {
                events.count { it is MpvNativeBridge.Event.Prepared } > preparedBeforeReload && replacementFrames.get() > framesBeforeReload
            }
            assertMediaCodecOutput(bridge, events)

            instrumentation.runOnMainSync {
                bridge.stop()
                assertTrue("Final Surface detach failed: ${bridge.lastError()}", bridge.setSurface(null))
                bridge.destroy()
                assertFalse("A released core must reject Surface binding", bridge.setSurface(replacementOutput!!.surface))
            }
            instrumentation.waitForIdleSync()
            val eventCountAfterRelease = events.size
            SystemClock.sleep(100)
            instrumentation.waitForIdleSync()
            assertEquals("Native events must stop after release", eventCountAfterRelease, events.size)
            assertTrue("Unexpected mpv error: $events", events.none { it is MpvNativeBridge.Event.Error })
        } finally {
            instrumentation.runOnMainSync {
                bridge.destroy()
                firstOutput?.setOnImageAvailableListener(null, null)
                firstOutput?.close()
                replacementOutput?.setOnImageAvailableListener(null, null)
                replacementOutput?.close()
            }
            fixture.delete()
        }
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
        instrumentation.runOnMainSync { assertEquals("mediacodec", bridge.hwdecCurrent()) }
        await("mediacodec_embed output log", events) {
            events.any { it is MpvNativeBridge.Event.LogMessage && it.message?.contains("VO: [mediacodec_embed]") == true }
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
