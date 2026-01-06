package com.xyoye.player.kernel.impl.media3.effect

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram

@UnstableApi
class Anime4kPerformanceGlEffect : GlEffect {
    @Throws(VideoFrameProcessingException::class)
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean
    ): GlShaderProgram {
        if (useHdr) {
            return PassthroughShaderProgram()
        }
        return runCatching { Anime4kPerformanceShaderProgram(context) }
            .getOrElse { PassthroughShaderProgram() }
    }
}
