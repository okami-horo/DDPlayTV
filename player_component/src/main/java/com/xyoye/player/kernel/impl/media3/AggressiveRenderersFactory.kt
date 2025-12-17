package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
class AggressiveRenderersFactory(
    context: Context,
    private val selector: MediaCodecSelector = AggressiveMediaCodecSelector()
) : DefaultRenderersFactory(context) {
    init {
        // 允许在首选解码器失败时回退其他实现
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // 强制使用自定义选择器，并开启解码器回退
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            selector,
            // enableDecoderFallback =
            true,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // 音频同样使用自定义选择器
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            selector,
            // enableDecoderFallback =
            true,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
