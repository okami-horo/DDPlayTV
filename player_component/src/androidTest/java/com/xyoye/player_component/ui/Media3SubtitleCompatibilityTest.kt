package com.xyoye.player_component.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
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
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.player.controller.subtitle.SubtitleImageView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.LibassAwareRenderersFactory
import com.xyoye.player.kernel.impl.media3.LibassExtractorsFactory
import com.xyoye.player.kernel.impl.media3.Media3MediaSourceHelper
import com.xyoye.player.kernel.impl.media3.Media3SubtitleFrameDriver
import com.xyoye.player.kernel.impl.media3.Media3SubtitleInput
import com.xyoye.player.kernel.impl.media3.Media3VideoPlayer
import com.xyoye.player.kernel.inter.VideoPlayerEventListener
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import com.xyoye.player.subtitle.backend.LibassEmbeddedSubtitleSink
import com.xyoye.player.subtitle.backend.SwitchableEmbeddedSubtitleSink
import com.xyoye.player.subtitle.gpu.AssGpuRenderer
import com.xyoye.player.subtitle.gpu.LocalSubtitlePipelineApi
import com.xyoye.player.subtitle.gpu.SubtitlePipelineController
import com.xyoye.subtitle.MixedSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Media3SubtitleCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer
    private var videoCallback: SubtitleFrameDriver.Callback? = null

    @Test
    fun ordinaryTextSize_isIndependentOfAssFontScale() {
        onMain {
            val settings = PlayerInitializer.Subtitle
            val oldScale = settings.fontScaleOffsetPercent
            try {
                settings.fontScaleOffsetPercent = 0
                val view = SubtitleImageView(instrumentation.targetContext)
                view.setSubtitle(listOf(Cue.Builder().setText("Native text size").build()))
                val specWidth = View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)
                val specHeight = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
                view.measure(specWidth, specHeight)
                view.layout(0, 0, 640, 360)
                val before = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                val after = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(before))
                    settings.fontScaleOffsetPercent = 200
                    view.updateStyle()
                    view.draw(Canvas(after))
                    assertTrue("ASS scale must not resize native text", before.sameAs(after))
                    val pixels = IntArray(640 * 360)
                    before.getPixels(pixels, 0, 640, 0, 0, 640, 360)
                    assertTrue("The fixture must render text", pixels.any { it != 0 })
                } finally {
                    before.recycle()
                    after.recycle()
                }
            } finally {
                settings.fontScaleOffsetPercent = oldScale
            }
        }
    }

    @Test
    fun kernelDefaultMediaItemFactory_preservesClippingMetadataAndAssInput() {
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "default-factory-video.mkv")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { source ->
            video.outputStream().use { source.copyTo(it) }
        }
        val kernel = Media3VideoPlayer(context)
        val sink = RecordingSink()
        try {
            onMain {
                kernel.initPlayer()
                kernel.setEmbeddedSubtitleSink(sink)
                player = kernel.exoPlayerOrNull()!!
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
                player.setMediaItem(
                    MediaItem
                        .Builder()
                        .setUri(Uri.fromFile(video))
                        .setMediaId("clipped-item")
                        .setMimeType(MimeTypes.VIDEO_MATROSKA)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Preserved title").build())
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration
                                .Builder()
                                .setStartPositionMs(500)
                                .setEndPositionMs(4500)
                                .build()
                        ).build()
                )
                player.prepare()
                player.pause()
            }
            await { sink.samples.any { it.text.contains("Before seek") } }
            assertEquals(-500_000L, sink.samples.first { it.text.contains("Before seek") }.timeUs)
            onMain {
                val item = player.currentMediaItem!!
                assertEquals("clipped-item", item.mediaId)
                assertEquals("Preserved title", item.mediaMetadata.title.toString())
                assertEquals(500L, item.clippingConfiguration.startPositionMs)
                assertEquals(4500L, item.clippingConfiguration.endPositionMs)
                assertEquals(MimeTypes.VIDEO_MATROSKA, item.localConfiguration!!.mimeType)
                assertEquals(null, player.playerError)
            }
        } finally {
            onMain { kernel.release() }
            video.delete()
        }
    }

    @Test
    fun externalAssSrtVttAndTtml_useRealTracksAndPreservePauseSpeedPositionAndQueue() {
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "external-track-video.mkv")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { source ->
            video.outputStream().use { source.copyTo(it) }
        }
        val kernel = Media3VideoPlayer(context)
        val sink = RecordingSink()
        val cues = CopyOnWriteArrayList<MixedSubtitle>()
        val errors = CopyOnWriteArrayList<Exception?>()
        val files = mutableListOf<File>()
        try {
            onMain {
                kernel.setPlayerEventListener(
                    object : VideoPlayerEventListener by VideoPlayerEventListener.NO_OP {
                        override fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
                            cues.add(subtitle)
                        }

                        override fun onError(e: Exception?) {
                            errors.add(e)
                        }
                    }
                )
                kernel.initPlayer()
                player = kernel.exoPlayerOrNull()!!
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
                kernel.setEmbeddedSubtitleSink(sink)
                kernel.setDataSource(video.path, null)
                kernel.prepareAsync()
                kernel.pause()
                kernel.setSpeed(1.5f)
            }
            await { onMain { player.playbackState == Player.STATE_READY } }
            onMain {
                player.addMediaSource(Media3MediaSourceHelper.getMediaSource(video.path, mediaId = "queued-next"))
                kernel.seekTo(1500)
            }
            await { onMain { player.playbackState == Player.STATE_READY && player.contentPosition == 1500L } }
            val ass =
                instrumentation.context.assets
                    .open("media3/subtitle-compatibility.ass")
                    .bufferedReader()
                    .use { it.readText() }
            val documents =
                listOf(
                    "srt" to "1\n00:00:00,000 --> 00:00:10,000\nExternal SRT\n\n",
                    "vtt" to "WEBVTT\n\n00:00.000 --> 00:10.000 line:20% position:25%\nExternal VTT\n\n",
                    "ttml" to
                        "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"0s\" end=\"10s\">External TTML</p></div></body></tt>",
                    "ass" to ass
                )
            for ((extension, content) in documents) {
                val file =
                    File(context.cacheDir, "external-track.$extension").also {
                        it.writeText(content)
                        files.add(it)
                    }
                cues.clear()
                assertTrue(onMain { kernel.addTrack(VideoTrackBean.subtitle(file.path)) })
                await { onMain { kernel.getTracks(TrackType.SUBTITLE).any { it.selected && it.name.contains(file.name) } } }
                await { onMain { player.playbackState == Player.STATE_READY } }
                onMain {
                    val expectedMime =
                        when (extension) {
                            "srt" -> MimeTypes.APPLICATION_SUBRIP
                            "vtt" -> MimeTypes.TEXT_VTT
                            "ttml" -> MimeTypes.APPLICATION_TTML
                            else -> MimeTypes.TEXT_SSA
                        }
                    assertEquals(expectedMime, kernel.getTracks(TrackType.SUBTITLE).single { it.selected }.subtitleMimeType)
                    assertEquals(2, player.mediaItemCount)
                    assertEquals("queued-next", player.getMediaItemAt(1).mediaId)
                    assertEquals(1500L, player.contentPosition)
                    assertEquals(false, player.playWhenReady)
                    assertEquals(1.5f, player.playbackParameters.speed)
                    assertEquals(1, kernel.getTracks(TrackType.SUBTITLE).count { it.selected })
                }
                val attachedItem = onMain { player.currentMediaItem }
                val attachedTracks = onMain { kernel.getTracks(TrackType.SUBTITLE).size }
                val duplicateCallbacks = AtomicInteger()
                assertTrue(
                    onMain {
                        kernel.addTrack(VideoTrackBean.subtitle(Uri.fromFile(file).toString())) {
                            duplicateCallbacks.incrementAndGet()
                        }
                    }
                )
                await { duplicateCallbacks.get() == 1 }
                onMain {
                    assertEquals("Adding the same resource must reuse its track", attachedTracks, kernel.getTracks(TrackType.SUBTITLE).size)
                    assertEquals("An attached subtitle must not recreate playback", attachedItem, player.currentMediaItem)
                }
                if (extension == "ass") {
                    await { sink.documents.any { it == content } }
                } else {
                    await { cues.any { subtitle -> subtitle.cues?.any { it.text.toString().contains("External") } == true } }
                    if (extension == "vtt") {
                        assertTrue(cues.any { subtitle -> subtitle.cues?.any { it.position == 0.25f && it.line == 0.2f } == true })
                    }
                    if (extension == "srt") {
                        try {
                            onMain {
                                // The controller saves the preference before notifying its kernel.
                                SubtitlePreferenceUpdater.persistOffset(-2500)
                                kernel.setSubtitleOffset(-2500)
                            }
                            await { cues.lastOrNull()?.cues?.isEmpty() == true }
                            await {
                                onMain {
                                    player.playbackState == Player.STATE_READY &&
                                        kernel.getTracks(TrackType.SUBTITLE).any { it.selected && it.name.contains(file.name) }
                                }
                            }
                            onMain { kernel.setSubtitleOffset(0) }
                            await { cues.lastOrNull()?.cues?.any { it.text.toString().contains("External SRT") } == true }
                            onMain { kernel.setSubtitleOffset(9000) }
                            await { cues.lastOrNull()?.cues?.isEmpty() == true }
                            await {
                                onMain {
                                    player.playbackState == Player.STATE_READY &&
                                        kernel.getTracks(TrackType.SUBTITLE).any { it.selected && it.name.contains(file.name) }
                                }
                            }
                        } finally {
                            onMain { kernel.setSubtitleOffset(0) }
                        }
                        await { cues.lastOrNull()?.cues?.any { it.text.toString().contains("External SRT") } == true }
                        onMain {
                            assertEquals(1500L, player.contentPosition)
                            assertEquals(false, player.playWhenReady)
                            assertEquals(1.5f, player.playbackParameters.speed)
                            assertEquals(2, player.mediaItemCount)
                            assertEquals("queued-next", player.getMediaItemAt(1).mediaId)
                        }
                    }
                }
            }
            val activeItem = onMain { player.currentMediaItem }
            val selectedTrack = onMain { kernel.getTracks(TrackType.SUBTITLE).single { it.selected } }
            val jobField = Media3VideoPlayer::class.java.getDeclaredField("subtitleLoadJob").apply { isAccessible = true }
            val invalid =
                File(context.cacheDir, "invalid-subtitle.srt").also {
                    it.writeText("no subtitle cues")
                    files.add(it)
                }
            val callbacks = AtomicInteger()
            val invalidJob =
                onMain {
                    assertTrue(kernel.addTrack(VideoTrackBean.subtitle(invalid.path)) { callbacks.incrementAndGet() })
                    jobField.get(kernel) as? Job
                }
            runBlocking { invalidJob?.join() }
            onMain {
                assertEquals(activeItem, player.currentMediaItem)
                assertEquals(selectedTrack.id, kernel.getTracks(TrackType.SUBTITLE).single { it.selected }.id)
            }
            repeat(20) {
                val cancelledJob =
                    onMain {
                        assertTrue(kernel.addTrack(VideoTrackBean.subtitle(files.first().path)) { callbacks.incrementAndGet() })
                        val job = jobField.get(kernel) as? Job
                        kernel.selectTrack(selectedTrack)
                        job
                    }
                runBlocking { cancelledJob?.join() }
            }
            onMain {
                assertEquals(activeItem, player.currentMediaItem)
                assertEquals(selectedTrack.id, kernel.getTracks(TrackType.SUBTITLE).single { it.selected }.id)
                assertEquals(1500L, player.contentPosition)
                assertEquals(false, player.playWhenReady)
                assertEquals(1.5f, player.playbackParameters.speed)
                assertEquals(2, player.mediaItemCount)
            }
            assertEquals(0, callbacks.get())
            assertTrue("subtitle additions must not fail playback: $errors", errors.isEmpty())
        } finally {
            onMain { kernel.release() }
            video.delete()
            files.forEach { it.delete() }
        }
    }

    @Test
    fun zlibAndPlainMkv_selectTheActualSimplifiedAndTraditionalAssTracks() {
        for (asset in listOf("subtitle-plain.mkv", "subtitle-zlib-explicit.mkv", "subtitle-zlib-default.mkv")) {
            val sink = RecordingSink()
            withPlayer(sink, false, fixtureAsset = asset) { player ->
                await { sink.samples.any { it.text.contains("简体") } }
                assertTrue(sink.samples.all { !it.text.contains("繁體") })
                instrumentation.runOnMainSync {
                    player.pause()
                    val (traditional, trackIndex) =
                        player.currentTracks.groups
                            .flatMap { group ->
                                if (group.type != C.TRACK_TYPE_TEXT) emptyList() else (0 until group.length).map { group to it }
                            }.first { (group, index) ->
                                group
                                    .getTrackFormat(index)
                                    .label
                                    .orEmpty()
                                    .contains("CHT")
                            }
                    sink.samples.clear()
                    player.trackSelectionParameters =
                        player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(traditional.mediaTrackGroup, trackIndex))
                            .build()
                }
                await { sink.samples.any { it.text.contains("繁體") } }
                assertTrue(sink.samples.all { !it.text.contains("简体") })
                val first = sink.samples.first { it.text.contains("Before seek") }
                assertEquals(0L, first.timeUs)
                assertEquals(1_000_000L, first.durationUs)
            }
        }
    }

    @Test
    fun realExtractorDecoderAndGpu_renderPixelsAfterSeekVisibilityAndSurfaceRecreation() {
        val thread = HandlerThread("Media3SubtitlePixels").apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val renderer = AssGpuRenderer(SubtitlePipelineController(LocalSubtitlePipelineApi()), Handler(thread.looper), scope = scope)
        val target = SubtitleOutputTarget(SubtitleViewType.SurfaceView, width = 640, height = 360)
        val firstOutput = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
        var secondOutput: ImageReader? = null
        val observed = RecordingSink()
        val forward = SwitchableEmbeddedSubtitleSink(LibassEmbeddedSubtitleSink(renderer, emptyList(), "/system/fonts/Roboto-Regular.ttf"))
        val sink =
            object : EmbeddedSubtitleSink by forward {
                override fun onFormat(codecPrivate: ByteArray?) {
                    forward.onFormat(codecPrivate)
                    observed.onFormat(codecPrivate)
                }

                override fun onSample(
                    data: ByteArray,
                    timeUs: Long,
                    durationUs: Long?
                ) {
                    forward.onSample(data, timeUs, durationUs)
                    observed.onSample(data, timeUs, durationUs)
                }

                override fun onFlush() {
                    forward.onFlush()
                    observed.onFlush()
                }
            }
        val input = Media3SubtitleInput()
        input.beginMedia()
        input.setSink(sink)
        val external = File(instrumentation.targetContext.cacheDir, "media3-external-recovery.ass")
        try {
            renderer.bindSurface("embedded-pixels", firstOutput.surface, target)
            awaitPixels(firstOutput, visible = false)
            withPlayer(sink, parseSubtitles = false, subtitleInput = input) { player ->
                await { observed.samples.any { it.text.contains("Before seek") } }
                renderer.renderFrame(500_000)
                awaitPixels(firstOutput, visible = true)
                renderer.renderFrame(1_000_000)
                awaitPixels(firstOutput, visible = false)

                val flushes = observed.flushes.get()
                observed.samples.clear()
                instrumentation.runOnMainSync { player.seekTo(3500) }
                await { observed.flushes.get() > flushes && observed.samples.any { it.text.contains("After seek") } }
                instrumentation.runOnMainSync { player.pause() }
                renderer.renderFrame(3_500_000)
                awaitPixels(firstOutput, visible = true)

                external.writeText(observed.codecPrivate.orEmpty() + "\nDialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,External\n")
                forward.setEnabled(false)
                assertTrue(runBlocking { renderer.loadTrack(external.path, emptyList(), "/system/fonts/Roboto-Regular.ttf") })
                awaitPixels(firstOutput, visible = false)
                renderer.renderFrame(3_500_000)
                awaitPixels(firstOutput, visible = true)
                renderer.clearEmbeddedTrack()
                forward.setEnabled(true)
                input.setSink(null)
                input.setSink(sink)
                renderer.renderFrame(3_500_000)
                awaitPixels(firstOutput, visible = true)

                forward.setEnabled(false)
                assertTrue(!runBlocking { renderer.loadTrack(external.path + ".missing", emptyList(), "/system/fonts/Roboto-Regular.ttf") })
                renderer.clearEmbeddedTrack()
                forward.setEnabled(true)
                input.setSink(null)
                input.setSink(sink)
                renderer.renderFrame(3_500_000)
                awaitPixels(firstOutput, visible = true)

                renderer.setVisible(false)
                awaitPixels(firstOutput, visible = false)
                renderer.setVisible(true)
                renderer.renderFrame(3_500_000)
                awaitPixels(firstOutput, visible = true)

                renderer.detachSurface()
                val replacement = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
                secondOutput = replacement
                renderer.bindSurface("embedded-pixels", replacement.surface, target)
                awaitPixels(replacement, visible = false)
                renderer.renderFrame(3_500_000)
                awaitPixels(replacement, visible = true)
            }
        } finally {
            renderer.release()
            thread.quitSafely()
            thread.join(1500)
            scope.cancel()
            firstOutput.close()
            secondOutput?.close()
            external.delete()
        }
    }

    private fun awaitPixels(
        output: ImageReader,
        visible: Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = output.acquireLatestImage()
            if (image != null) {
                val hasPixels =
                    image.use {
                        val plane = it.planes[0]
                        (0 until it.height).any { y ->
                            (0 until it.width).any { x -> plane.buffer.get(y * plane.rowStride + x * plane.pixelStride + 3).toInt() != 0 }
                        }
                    }
                if (hasPixels == visible) return
            }
            SystemClock.sleep(10)
        }
        throw AssertionError("Media3 subtitle output did not reach visible=$visible")
    }

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
                val firstFactory =
                    ProgressiveMediaSource.Factory(
                        DefaultDataSource.Factory(instrumentation.targetContext),
                        LibassExtractorsFactory(firstFonts, first)
                    )
                val secondFactory =
                    ProgressiveMediaSource.Factory(
                        DefaultDataSource.Factory(instrumentation.targetContext),
                        LibassExtractorsFactory(secondFonts, second)
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
                    firstFactory.createMediaSource(
                        source.mediaItem
                            .buildUpon()
                            .setTag(first)
                            .build()
                    ),
                    secondFactory.createMediaSource(
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
        subtitleInput: Media3SubtitleInput? = null,
        fixtureAsset: String = "subtitle-compatibility.mkv",
        verify: (ExoPlayer) -> Unit
    ) {
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "media3-subtitle-compatibility.mkv")
        instrumentation.context.assets.open("media3/$fixtureAsset").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        var videoOutput: ImageReader? = null
        var driver: Media3SubtitleFrameDriver? = null
        try {
            instrumentation.runOnMainSync {
                player =
                    ExoPlayer
                        .Builder(context)
                        .setRenderersFactory(
                            LibassAwareRenderersFactory(context)
                        ).build()
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
                val input = subtitleInput ?: Media3SubtitleInput()
                val fonts = input.beginMedia()
                input.setSink(sink)
                player.setMediaSource(
                    sourceTransform(
                        ProgressiveMediaSource
                            .Factory(
                                DefaultDataSource.Factory(context),
                                if (parseSubtitles) DefaultExtractorsFactory() else LibassExtractorsFactory(fonts, input)
                            ).createMediaSource(
                                MediaItem
                                    .Builder()
                                    .setUri(Uri.fromFile(fixture))
                                    .setTag(input)
                                    .build()
                            )
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

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private class RecordingSink : EmbeddedSubtitleSink {
        @Volatile var codecPrivate: String? = null
        val samples = CopyOnWriteArrayList<Sample>()
        val flushes = AtomicInteger()
        val releases = AtomicInteger()
        val formats = AtomicInteger()
        val fonts = CopyOnWriteArrayList<String>()
        val documents = CopyOnWriteArrayList<String>()

        override fun onMediaChanged() {
            fonts.clear()
            samples.clear()
            documents.clear()
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

        override fun onDocument(
            data: ByteArray,
            timeOffsetUs: Long
        ) {
            documents.add(data.toString(Charsets.UTF_8))
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
