package com.xyoye.player.kernel.impl.media3.effect

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import com.xyoye.player.kernel.anime4k.Anime4kShaderAssets

@UnstableApi
class Anime4kPerformanceShaderProgram(
    context: Context,
    outputSizeProvider: () -> Size?
) : Anime4kMpvShaderProgram(
        context = context,
        outputSizeProvider = outputSizeProvider,
        shaderFiles = Anime4kShaderAssets.performanceShaders,
    )
