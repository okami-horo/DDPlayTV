package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.xyoye.common_component.config.SubtitlePreferenceUpdater

@UnstableApi
class LibassAwareRenderersFactory(
    context: Context
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
            ).map {
                when (it.trackType) {
                    C.TRACK_TYPE_VIDEO -> LibassTimelineRenderer(it)
                    C.TRACK_TYPE_TEXT -> LibassTimelineRenderer(it) { SubtitlePreferenceUpdater.currentOffset() * 1000L }
                    else -> it
                }
            }.toTypedArray()

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
}
