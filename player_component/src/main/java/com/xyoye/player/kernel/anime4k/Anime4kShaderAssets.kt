package com.xyoye.player.kernel.anime4k

/**
 * Anime4K shader 资源清单（assets/shaders）。
 *
 * 目标：让不同播放器内核（mpv / media3）复用同一套 shader 文件列表，避免模式含义漂移。
 */
object Anime4kShaderAssets {
    const val ASSET_DIR = "shaders"

    val performanceShaders: List<String> =
        listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )

    val qualityShaders: List<String> =
        listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        )
}

