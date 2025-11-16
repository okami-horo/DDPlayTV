package com.xyoye.player.subtitle.backend

import android.content.Context
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.subtitle.SubtitleController

/**
 * Carries dependencies that subtitle backends require to hook into the player UI tree.
 */
data class SubtitleRenderEnvironment(
    val context: Context,
    val subtitleController: SubtitleController,
    val playerView: DanDanVideoPlayer,
    val fallbackDispatcher: SubtitleFallbackDispatcher
)
