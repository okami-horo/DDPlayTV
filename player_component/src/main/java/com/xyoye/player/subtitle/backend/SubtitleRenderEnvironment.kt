package com.xyoye.player.subtitle.backend

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.subtitle.SubtitleController
import java.lang.ref.WeakReference

/**
 * Carries dependencies that subtitle backends require to hook into the player UI tree.
 */
@UnstableApi
class SubtitleRenderEnvironment(
    context: Context,
    subtitleController: SubtitleController,
    playerView: DanDanVideoPlayer,
    fallbackDispatcher: SubtitleFallbackDispatcher
) {
    val context: Context = context.applicationContext

    private val subtitleControllerRef = WeakReference(subtitleController)
    private val playerViewRef = WeakReference(playerView)
    private val fallbackDispatcherRef = WeakReference(fallbackDispatcher)

    val subtitleController: SubtitleController?
        get() = subtitleControllerRef.get()

    val playerView: DanDanVideoPlayer?
        get() = playerViewRef.get()

    val fallbackDispatcher: SubtitleFallbackDispatcher?
        get() = fallbackDispatcherRef.get()

    fun clearUiReferences() {
        subtitleControllerRef.clear()
        playerViewRef.clear()
        fallbackDispatcherRef.clear()
    }
}
