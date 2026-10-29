package com.xyoye.player_component.ui

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3Diagnostics
import com.xyoye.player.kernel.impl.media3.Media3VideoPlayer
import com.xyoye.player.kernel.inter.VideoPlayerEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class Media3DecoderLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var kernel: Media3VideoPlayer

    @Test
    fun networkErrorAfterDecoderInitialization_reachesOwnerWithoutRestart() {
        withKernel { kernel, actualEvents, _ ->
            awaitVideoDecoder(actualEvents)
            val errors = mutableListOf<Exception?>()
            val error =
                PlaybackException(
                    "Network connection lost",
                    IOException("Connection interrupted"),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                )
            instrumentation.runOnMainSync {
                val player = kernel.exoPlayerOrNull()!!
                kernel.setPlayerEventListener(
                    object : VideoPlayerEventListener by VideoPlayerEventListener.NO_OP {
                        override fun onError(e: Exception?) {
                            errors.add(e)
                        }
                    }
                )
                assertEquals(Player.STATE_READY, player.playbackState)
                kernel.onPlayerError(error)
                assertEquals("The kernel must deliver the original source error", 1, errors.size)
                assertSame(error, errors.single())
                assertEquals("A source error must not restart the decoder", Player.STATE_READY, player.playbackState)
            }
        }
    }

    @Test
    fun decoderDiagnostics_matchRealInitializationAndReleaseAcrossRebuild() {
        withKernel { kernel, actualEvents, diagnosticEvents ->
            awaitVideoDecoder(actualEvents)
            await { diagnosticEvents.any { it.name == "decoder_initialized" && it.data["track"] == "video" } }
            assertDecoderEventsMatch(actualEvents, diagnosticEvents)

            instrumentation.runOnMainSync {
                val player = kernel.exoPlayerOrNull()!!
                assertTrue("Default video selection must remain adaptive", player.trackSelectionParameters.overrides.isEmpty())
                assertTrue(player.trackSelectionParameters.preferredVideoMimeTypes.isEmpty())
                PlayerInitializer.Subtitle.backend = SubtitleRendererBackend.LIBASS
                kernel.onSubtitleBackendChanged(SubtitleRendererBackend.LIBASS)
                observeDecoderEvents(kernel.exoPlayerOrNull()!!, actualEvents)
            }
            await { actualEvents.count { it.name == "decoder_initialized" && it.data["track"] == "video" } >= 2 }
            await { diagnosticEvents.count { it.name == "decoder_initialized" && it.data["track"] == "video" } >= 2 }
            instrumentation.runOnMainSync { kernel.release() }
            await { diagnosticEvents.count { it.name == "decoder_released" && it.data["track"] == "video" } >= 2 }
            instrumentation.waitForIdleSync()
            assertDecoderEventsMatch(actualEvents, diagnosticEvents)
        }
    }

    private fun assertDecoderEventsMatch(
        actual: List<Event>,
        diagnostics: List<Event>
    ) {
        val names = setOf("decoder_initialized", "decoder_released")
        assertTrue(actual.any { it.name == "decoder_initialized" && it.data["track"] == "audio" })
        assertEquals(actual.filter { it.name in names }, diagnostics.filter { it.name in names })
        diagnostics.filter { it.name == "decoder_initialized" }.forEach {
            assertTrue(it.data.getValue("initializationDurationMs").toLong() >= 0)
        }
    }

    private fun withKernel(verify: (Media3VideoPlayer, MutableList<Event>, List<Event>) -> Unit) {
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "media3-decoder-lifecycle.mkv")
        instrumentation.context.assets.open("media3/decoder-lifecycle.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val oldBackend = PlayerInitializer.Subtitle.backend
        val oldTelemetryListener = Media3Diagnostics.telemetryListener
        val actualEvents = CopyOnWriteArrayList<Event>()
        val diagnosticEvents = CopyOnWriteArrayList<Event>()
        try {
            instrumentation.runOnMainSync {
                PlayerInitializer.Subtitle.backend = SubtitleRendererBackend.LEGACY_CANVAS
                Media3Diagnostics.telemetryListener = { name, data -> diagnosticEvents.add(Event(name, data)) }
                kernel = Media3VideoPlayer(context)
                kernel.initPlayer()
                assertTrue(diagnosticEvents.none { it.name == "decoder_initialized" })
                observeDecoderEvents(kernel.exoPlayerOrNull()!!, actualEvents)
                kernel.setDataSource(Uri.fromFile(fixture).toString())
                kernel.prepareAsync()
                kernel.pause()
            }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = kernel.exoPlayerOrNull()!!.playbackState == Player.STATE_READY }
                ready
            }
            verify(kernel, actualEvents, diagnosticEvents)
        } finally {
            instrumentation.runOnMainSync {
                if (::kernel.isInitialized) kernel.release()
                PlayerInitializer.Subtitle.backend = oldBackend
                Media3Diagnostics.telemetryListener = oldTelemetryListener
            }
            fixture.delete()
        }
    }

    private fun observeDecoderEvents(
        player: ExoPlayer,
        events: MutableList<Event>
    ) {
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    events.add(
                        Event(
                            "decoder_initialized",
                            mapOf(
                                "track" to "video",
                                "decoder" to decoderName,
                                "initializedTimestampMs" to initializedTimestampMs.toString(),
                                "initializationDurationMs" to initializationDurationMs.toString()
                            )
                        )
                    )
                }

                override fun onVideoDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String
                ) {
                    events.add(Event("decoder_released", mapOf("track" to "video", "decoder" to decoderName)))
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    events.add(
                        Event(
                            "decoder_initialized",
                            mapOf(
                                "track" to "audio",
                                "decoder" to decoderName,
                                "initializedTimestampMs" to initializedTimestampMs.toString(),
                                "initializationDurationMs" to initializationDurationMs.toString()
                            )
                        )
                    )
                }

                override fun onAudioDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String
                ) {
                    events.add(Event("decoder_released", mapOf("track" to "audio", "decoder" to decoderName)))
                }
            }
        )
    }

    private fun awaitVideoDecoder(events: List<Event>) {
        await { events.any { it.name == "decoder_initialized" && it.data["track"] == "video" } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("Expected Media3 decoder lifecycle event within 10 seconds", condition())
    }

    private data class Event(
        val name: String,
        val data: Map<String, String>
    )
}
