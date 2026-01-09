package com.xyoye.player.kernel.impl.media3.effect

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import com.xyoye.player.kernel.impl.media3.Media3Diagnostics

@UnstableApi
class Anime4kPerformanceGlEffect(
    private val outputSizeProvider: () -> Size?,
) : GlEffect {
    @Throws(VideoFrameProcessingException::class)
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean
    ): GlShaderProgram {
        if (useHdr) {
            Media3Diagnostics.logAnime4kGlEffectDecision(
                useHdr = true,
                decision = "skip_passthrough_due_to_hdr",
            )
            return PassthroughShaderProgram()
        }
        val shaderProgram =
            runCatching { Anime4kPerformanceShaderProgram(context, outputSizeProvider) }
                .onSuccess {
                    Media3Diagnostics.logAnime4kGlEffectDecision(
                        useHdr = false,
                        decision = "create_shader_program_success",
                    )
                }.onFailure { error ->
                    Media3Diagnostics.logAnime4kGlEffectDecision(
                        useHdr = false,
                        decision = "create_shader_program_failed_fallback_passthrough",
                        throwable = error,
                    )
                }.getOrElse { null }
        return shaderProgram ?: PassthroughShaderProgram()
    }
}
