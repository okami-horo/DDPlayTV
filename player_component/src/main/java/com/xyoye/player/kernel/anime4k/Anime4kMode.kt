package com.xyoye.player.kernel.anime4k

/**
 * Anime4K 模式约定：
 * - 0 关闭
 * - 1 性能
 * - 2 质量
 */
object Anime4kMode {
    const val MODE_OFF = 0
    const val MODE_PERFORMANCE = 1
    const val MODE_QUALITY = 2

    fun normalize(mode: Int): Int =
        when (mode) {
            MODE_PERFORMANCE -> MODE_PERFORMANCE
            MODE_QUALITY -> MODE_QUALITY
            else -> MODE_OFF
        }
}
