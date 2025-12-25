package com.xyoye.player.kernel.subtitle

import androidx.media3.common.util.UnstableApi
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink

/**
 * Kernel-specific integration point for the subtitle pipeline.
 *
 * Kernels may provide:
 * - Embedded ASS/SSA samples via [EmbeddedSubtitleSink]
 * - Video frame callbacks for precise subtitle scheduling
 */
@androidx.annotation.OptIn(UnstableApi::class)
interface SubtitleKernelBridge {
    /**
     * Whether the current kernel configuration supports attaching the GPU subtitle overlay.
     *
     * For example, mpv can only guarantee correct z-ordering when using `vo=mediacodec_embed`.
     */
    fun canStartGpuSubtitlePipeline(): Boolean = true

    /**
     * Injects the current embedded subtitle sink.
     *
     * Kernels that do not expose embedded subtitle samples can ignore this call.
     */
    fun setEmbeddedSubtitleSink(sink: EmbeddedSubtitleSink?) {
        // default no-op
    }

    /**
     * Creates a kernel-driven frame driver. Return null when the kernel cannot provide
     * accurate frame callbacks (the session will fall back to choreographer polling).
     */
    fun createFrameDriver(callback: SubtitleFrameDriver.Callback): SubtitleFrameDriver? = null
}
