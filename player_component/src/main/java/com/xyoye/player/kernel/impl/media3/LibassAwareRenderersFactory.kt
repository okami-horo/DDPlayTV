package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSinkRegistry

@UnstableApi
class LibassAwareRenderersFactory(
    context: Context,
    selector: MediaCodecSelector = AggressiveMediaCodecSelector()
) : AggressiveRenderersFactory(context, selector) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        if (PlayerInitializer.Subtitle.backend == SubtitleRendererBackend.LIBASS) {
            val decoderFactory = LibassSubtitleDecoderFactory { EmbeddedSubtitleSinkRegistry.current() }
            out.add(TextRenderer(output, outputLooper, decoderFactory))
            return
        }
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    }
}
