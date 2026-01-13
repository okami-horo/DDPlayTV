package com.xyoye.player.subtitle.gpu

/**
 * Flushes native subtitle textures to avoid ghost frames when timeline jumps.
 */
class SubtitleFrameCleaner(
    private val flushAction: () -> Unit
) {
    fun onSeek() {
        flushAction()
    }

    fun onTrackChanged() {
        flushAction()
    }

    fun onSurfaceLost() {
        flushAction()
    }
}
