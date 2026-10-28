package com.xyoye.player_component.ui

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.player.kernel.impl.media3.LibassAwareRenderersFactory
import com.xyoye.player.kernel.impl.media3.RewritingExtractorsFactory
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
        verify: (ExoPlayer) -> Unit
    ) {
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "media3-subtitle-compatibility.mkv")
        instrumentation.context.assets.open("media3/subtitle-compatibility.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
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
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
                player.addListener(
                    object : Player.Listener {
                        override fun onCues(cueGroup: CueGroup) {
                            onCues(cueGroup.cues.mapNotNull { it.text?.toString() })
                        }
                    }
                )
                player.setMediaSource(
                    ProgressiveMediaSource
                        .Factory(
                            DefaultDataSource.Factory(context),
                            RewritingExtractorsFactory(parseSubtitles)
                        ).createMediaSource(MediaItem.fromUri(Uri.fromFile(fixture)))
                )
                player.prepare()
                player.play()
            }
            verify(player)
            instrumentation.runOnMainSync { assertEquals(null, player.playerError) }
        } finally {
            instrumentation.runOnMainSync { if (::player.isInitialized) player.release() }
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

        override fun onFormat(codecPrivate: ByteArray?) {
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

        override fun onRelease() = Unit
    }

    private data class Sample(
        val text: String,
        val timeUs: Long,
        val durationUs: Long?
    )
}
