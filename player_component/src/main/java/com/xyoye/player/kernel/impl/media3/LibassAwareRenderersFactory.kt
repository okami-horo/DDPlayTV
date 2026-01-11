package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink

@UnstableApi
class LibassAwareRenderersFactory(
    context: Context,
    selector: MediaCodecSelector = AggressiveMediaCodecSelector(),
    private val embeddedSinkProvider: () -> EmbeddedSubtitleSink?
) : AggressiveRenderersFactory(context, selector) {
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
        out.add(insertIndex, TextRenderer(output, outputLooper, decoderFactory))
    }
}
