package com.xyoye.player_component.ui

import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.player.kernel.impl.media3.LibassAwareRenderersFactory
import com.xyoye.player.kernel.impl.media3.Media3SubtitleFrameDriver
import com.xyoye.player.kernel.impl.media3.Media3SubtitleInput
import com.xyoye.player.kernel.impl.media3.Media3VideoPlayer
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class Media3SubtitleCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer
    private var videoCallback: SubtitleFrameDriver.Callback? = null

    @Test
    fun preloadedPlaylist_activatesTheMatchingInputAndIsolatesLatePreviousMediaFonts() {
        val sink = RecordingSink()
        val first = Media3SubtitleInput()
        val second = Media3SubtitleInput()
        val firstFonts = first.beginMedia()
        val secondFonts = second.beginMedia()
        assertTrue(firstFonts.reserve(4))
        firstFonts.onFont("first.ttf", byteArrayOf(0, 1, 0, 0))
        assertTrue(secondFonts.reserve(4))
        secondFonts.onFont("second.ttf", byteArrayOf(0, 1, 0, 0))
        val kernel = Media3VideoPlayer(instrumentation.targetContext)
        instrumentation.runOnMainSync { kernel.setEmbeddedSubtitleSink(sink) }
        try {
            withPlayer(sink, false, sourceTransform = { source ->
                val factory =
                    ProgressiveMediaSource.Factory(
                        DefaultDataSource.Factory(instrumentation.targetContext),
                        DefaultExtractorsFactory().experimentalSetTextTrackTranscodingEnabled(false)
                    )
                player.addListener(
                    object : Player.Listener {
                        override fun onMediaItemTransition(
                            mediaItem: MediaItem?,
                            reason: Int
                        ) {
                            kernel.onMediaItemTransition(mediaItem, reason)
                        }
                    }
                )
                ConcatenatingMediaSource(
                    factory.createMediaSource(
                        source.mediaItem
                            .buildUpon()
                            .setTag(first)
                            .build()
                    ),
                    factory.createMediaSource(
                        source.mediaItem
                            .buildUpon()
                            .setTag(second)
                            .build()
                    )
                )
            }) {
                await { sink.fonts == listOf("first.ttf") && sink.samples.isNotEmpty() }
                await { sink.fonts == listOf("second.ttf") && sink.samples.any { it.text.contains("Before seek") } }
                assertTrue(firstFonts.reserve(4))
                firstFonts.onFont("late-first.ttf", byteArrayOf(0, 1, 0, 0))
                assertEquals(listOf("second.ttf"), sink.fonts)
                assertTrue("new media must start in its own window", sink.samples.all { it.timeUs < 5_000_000L })
            }
        } finally {
            instrumentation.runOnMainSync { kernel.setEmbeddedSubtitleSink(null) }
        }
    }

    @Test
    fun videoMetadata_mapsClippedFramesAndNaturalPeriodTransitionWithoutReadAheadDrift() {
        val frames = CopyOnWriteArrayList<Long>()
        withPlayer(RecordingSink(), false, videoFrames = frames, sourceTransform = {
            ClippingMediaSource(it, 2_000_000, 5_000_000)
        }) {
            await { frames.any { it >= 1_000_000L } }
            assertTrue("clipped video PTS must use the subtitle window coordinate: $frames", frames.first() in 0L..500_000L)
            assertTrue(frames.all { it in 0L..3_000_000L })
        }
        frames.clear()
        withPlayer(RecordingSink(), false, videoFrames = frames, sourceTransform = {
            ConcatenatingMediaSource2
                .Builder()
                .add(it, 5000)
                .add(it, 5000)
                .build()
        }) { player ->
            await { frames.any { it >= 5_500_000L } }
            assertTrue(
                "decoded frames must keep their own period mapping across read-ahead: $frames",
                frames.zipWithNext().all { (before, after) ->
                    after >= before && after - before <= 500_000L
                }
            )
            instrumentation.runOnMainSync {
                frames.clear()
                player.seekTo(8500)
            }
            await { frames.any { it in 8_500_000L..9_000_000L } }
            instrumentation.runOnMainSync {
                frames.clear()
                player.seekTo(0)
            }
            await { frames.any { it in 0L..500_000L } }
        }
    }

    @Test
    fun clippingNonzeroStart_mapsAssToWindowTimeAtEveryPlaybackSpeed() {
        for (speed in listOf(0.5f, 1f, 2f)) {
            val sink = RecordingSink()
            withPlayer(sink, false, sourceTransform = { ClippingMediaSource(it, 2_000_000, 5_000_000) }) { player ->
                instrumentation.runOnMainSync { player.setPlaybackSpeed(speed) }
                await { sink.samples.any { it.text.contains("After seek") } }
                assertEquals(1_000_000L, sink.samples.first { it.text.contains("After seek") }.timeUs)
                val flushes = sink.flushes.get()
                instrumentation.runOnMainSync {
                    player.pause()
                    player.seekTo(1000)
                }
                instrumentation.runOnMainSync { player.play() }
                await { sink.flushes.get() > flushes }
            }
        }
    }

    @Test
    fun multiplePeriods_mapAssToOneWindowAcrossForwardAndBackwardSeek() {
        val sink = RecordingSink()
        withPlayer(sink, false, sourceTransform = { source ->
            ConcatenatingMediaSource2
                .Builder()
                .add(source, 5000)
                .add(source, 5000)
                .build()
        }) { player ->
            await { sink.samples.any { it.text.contains("Before seek") } }
            sink.samples.clear()
            instrumentation.runOnMainSync { player.seekTo(8500) }
            await { sink.samples.any { it.text.contains("After seek") && it.timeUs > 5_000_000L } }
            assertEquals(8_000_000L, sink.samples.first { it.text.contains("After seek") && it.timeUs > 5_000_000L }.timeUs)
            sink.samples.clear()
            instrumentation.runOnMainSync { player.seekTo(0) }
            await { sink.samples.any { it.text.contains("Before seek") && it.timeUs < 5_000_000L } }
        }
    }

    @Test
    fun missingMetadata_usesPositionThenYieldsToMetadataAndStopsCleanly() {
        val frames = CopyOnWriteArrayList<Pair<Long, Long?>>()
        val callback =
            object : SubtitleFrameDriver.Callback {
                override fun onVideoFrame(
                    contentTimeUs: Long,
                    targetPresentTimeNs: Long?
                ) {
                    frames.add(contentTimeUs to targetPresentTimeNs)
                }

                override fun onTimelineJump(
                    positionMs: Long,
                    playing: Boolean,
                    reason: SubtitleFrameDriver.TimelineJumpReason
                ) = Unit

                override fun onPlaybackEnded() = Unit
            }
        withPlayer(RecordingSink(), false) { player ->
            lateinit var driver: Media3SubtitleFrameDriver
            instrumentation.runOnMainSync {
                driver = Media3SubtitleFrameDriver(player, callback)
                driver.start()
            }
            try {
                await { frames.count { it.second == null && it.first > 0 } >= 2 }
                frames.clear()
                val target = System.nanoTime() + 20_000_000L
                instrumentation.runOnMainSync {
                    driver.onVideoFrameAboutToBeRendered(
                        123_456L,
                        target,
                        androidx.media3.common.Format
                            .Builder()
                            .build(),
                        null
                    )
                }
                await { frames.any { it == 123_456L to target } }
                val count = frames.size
                SystemClock.sleep(100)
                assertEquals("recent metadata must suppress position ticks", count, frames.size)
                await { frames.size > count }
                instrumentation.runOnMainSync {
                    driver.onVideoFrameAboutToBeRendered(
                        654_321L,
                        C.TIME_UNSET,
                        androidx.media3.common.Format
                            .Builder()
                            .build(),
                        null
                    )
                }
                await { frames.any { it == 654_321L to null } }
            } finally {
                instrumentation.runOnMainSync { driver.stop() }
            }
            val stopped = frames.size
            SystemClock.sleep(300)
            assertEquals(stopped, frames.size)
        }
    }

    @Test
    fun disablingAndReenablingText_releasesAndRecreatesEmbeddedTrack() {
        val sink = RecordingSink()
        withPlayer(sink, parseSubtitles = false) { player ->
            await { sink.samples.any { it.text.contains("Before seek") } }
            instrumentation.runOnMainSync {
                player.pause()
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
            }
            await { sink.releases.get() == 1 }
            sink.samples.clear()
            instrumentation.runOnMainSync {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                player.seekTo(0)
                player.play()
            }
            await { sink.samples.any { it.text.contains("Before seek") } }
            assertTrue(sink.formats.get() >= 2)
        }
    }

    @Test
    fun embeddedAssReachesLibassBridgeAfterForwardAndBackwardSeek() {
        val sink = RecordingSink()
        withPlayer(sink, parseSubtitles = false) { player ->
            await { sink.samples.any { it.text.contains("Before seek") } }
            assertTrue(sink.codecPrivate?.contains("[Events]") == true)
            val initialFlushes = sink.flushes.get()
            sink.samples.clear()
            instrumentation.runOnMainSync { player.seekTo(3500) }
            await { sink.flushes.get() > initialFlushes && sink.samples.any { it.text.contains("After seek") } }
            val sample = sink.samples.first { it.text.contains("After seek") }
            assertEquals(3_000_000L, sample.timeUs)
            assertEquals(1_000_000L, sample.durationUs)

            sink.samples.clear()
            instrumentation.runOnMainSync { player.seekTo(0) }
            await { sink.samples.any { it.text.contains("Before seek") } }
        }
    }

    @Test
    fun extractedAssStillProducesCuesWithoutLibassSink() {
        val cues = CopyOnWriteArrayList<String>()
        withPlayer(null, parseSubtitles = true, onCues = { cues.addAll(it) }) { player ->
            await { cues.any { it.contains("Before seek") } }
            cues.clear()
            instrumentation.runOnMainSync { player.seekTo(3500) }
            await { cues.any { it.contains("After seek") } }
        }
    }

    private fun withPlayer(
        sink: EmbeddedSubtitleSink?,
        parseSubtitles: Boolean,
        onCues: (List<String>) -> Unit = {},
        sourceTransform: (MediaSource) -> MediaSource = { it },
        videoFrames: MutableList<Long>? = null,
        verify: (ExoPlayer) -> Unit
    ) {
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "media3-subtitle-compatibility.mkv")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        var videoOutput: ImageReader? = null
        var driver: Media3SubtitleFrameDriver? = null
        try {
            instrumentation.runOnMainSync {
                player =
                    ExoPlayer
                        .Builder(context)
                        .setRenderersFactory(LibassAwareRenderersFactory(context, embeddedSinkProvider = { sink }))
                        .build()
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoFrames == null)
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
                if (videoFrames != null) {
                    videoOutput =
                        ImageReader.newInstance(64, 64, ImageFormat.YUV_420_888, 3).apply {
                            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(player.applicationLooper))
                        }
                    player.setVideoSurface(videoOutput!!.surface)
                    videoCallback =
                        object : SubtitleFrameDriver.Callback {
                            override fun onVideoFrame(
                                contentTimeUs: Long,
                                targetPresentTimeNs: Long?
                            ) {
                                // Count real codec output only, excluding the missing-metadata position driver.
                                if (targetPresentTimeNs != null) videoFrames.add(contentTimeUs)
                            }

                            override fun onTimelineJump(
                                positionMs: Long,
                                playing: Boolean,
                                reason: SubtitleFrameDriver.TimelineJumpReason
                            ) = Unit

                            override fun onPlaybackEnded() = Unit
                        }
                    driver = Media3SubtitleFrameDriver(player, videoCallback!!).also { it.start() }
                }
                player.addListener(
                    object : Player.Listener {
                        override fun onCues(cueGroup: CueGroup) {
                            onCues(cueGroup.cues.mapNotNull { it.text?.toString() })
                        }
                    }
                )
                player.setMediaSource(
                    sourceTransform(
                        ProgressiveMediaSource
                            .Factory(
                                DefaultDataSource.Factory(context),
                                DefaultExtractorsFactory().experimentalSetTextTrackTranscodingEnabled(parseSubtitles)
                            ).createMediaSource(MediaItem.fromUri(Uri.fromFile(fixture)))
                    )
                )
                player.prepare()
                player.play()
            }
            verify(player)
            instrumentation.runOnMainSync { assertEquals(null, player.playerError) }
        } finally {
            instrumentation.runOnMainSync {
                driver?.stop()
                videoCallback = null
                if (::player.isInitialized) player.release()
                videoOutput?.close()
            }
            fixture.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("Media3 did not deliver the expected subtitle within 10 seconds", condition())
    }

    private class RecordingSink : EmbeddedSubtitleSink {
        @Volatile var codecPrivate: String? = null
        val samples = CopyOnWriteArrayList<Sample>()
        val flushes = AtomicInteger()
        val releases = AtomicInteger()
        val formats = AtomicInteger()
        val fonts = CopyOnWriteArrayList<String>()

        override fun onMediaChanged() {
            fonts.clear()
            samples.clear()
        }

        override fun onFont(
            name: String,
            data: ByteArray
        ) {
            fonts.add(name)
        }

        override fun onFormat(codecPrivate: ByteArray?) {
            formats.incrementAndGet()
            this.codecPrivate = codecPrivate?.toString(Charsets.UTF_8)
        }

        override fun onSample(
            data: ByteArray,
            timeUs: Long,
            durationUs: Long?
        ) {
            samples.add(Sample(data.toString(Charsets.UTF_8), timeUs, durationUs))
        }

        override fun onFlush() {
            flushes.incrementAndGet()
        }

        override fun onRelease() {
            releases.incrementAndGet()
        }
    }

    private data class Sample(
        val text: String,
        val timeUs: Long,
        val durationUs: Long?
    )
}
