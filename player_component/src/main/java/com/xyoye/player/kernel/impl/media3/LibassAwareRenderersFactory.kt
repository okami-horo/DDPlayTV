package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink

@UnstableApi
class LibassAwareRenderersFactory(
    context: Context,
    private val embeddedSinkProvider: () -> EmbeddedSubtitleSink?
) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> =
        super
            .createRenderers(
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput
            ).map { if (it.trackType == C.TRACK_TYPE_VIDEO) LibassTimelineRenderer(it) else it }
            .toTypedArray()

    override fun createSecondaryRenderer(
        renderer: Renderer,
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Renderer? =
        super
            .createSecondaryRenderer(
                (renderer as? LibassTimelineRenderer)?.delegateRenderer ?: renderer,
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput
            )?.let { LibassTimelineRenderer(it) }

    // Raw ASS samples still need the legacy decoder path for the libass bridge.
    @androidx.annotation.OptIn(ExperimentalApi::class)
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)

        val decoderFactory = LibassSubtitleDecoderFactory(embeddedSinkProvider)
        val insertIndex = out.indexOfFirst { it is TextRenderer }.takeIf { it >= 0 } ?: out.size
        out.removeAll { it is TextRenderer }
        val textRenderer =
            TextRenderer(output, outputLooper, decoderFactory).apply {
                experimentalSetLegacyDecodingEnabled(true)
            }
        out.add(insertIndex, LibassTimelineRenderer(textRenderer, decoderFactory))
    }
}
