package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.PathHelper
import java.io.File
import java.io.FileOutputStream

internal object Anime4kShaderManager {
    private const val TAG = "Anime4kShaderManager"
    private const val ASSET_SHADER_DIR = "shaders"
    private const val OUTPUT_DIR_NAME = "anime4k"

    const val MODE_OFF = 0
    const val MODE_PERFORMANCE = 1
    const val MODE_QUALITY = 2

    private val qualityShaders =
        arrayOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl"
        )

    private val performanceShaders =
        arrayOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        )

    fun resolveShaderPaths(
        context: Context,
        mode: Int
    ): List<String> {
        val safeMode =
            when (mode) {
                MODE_PERFORMANCE -> MODE_PERFORMANCE
                MODE_QUALITY -> MODE_QUALITY
                else -> MODE_OFF
            }
        if (safeMode == MODE_OFF) {
            return emptyList()
        }

        val directory = ensureShadersDirectory(context) ?: return emptyList()
        val shaderFiles =
            when (safeMode) {
                MODE_PERFORMANCE -> performanceShaders
                MODE_QUALITY -> qualityShaders
                else -> emptyArray()
            }

        val resolved = ArrayList<String>(shaderFiles.size)
        shaderFiles.forEach { filename ->
            val target = ensureShaderFile(context, directory, filename) ?: return emptyList()
            resolved.add(target.absolutePath)
        }
        return resolved
    }

    private fun ensureShadersDirectory(context: Context): File? {
        val baseDir = PathHelper.getMpvShadersDirectory()
        val directory =
            File(baseDir, OUTPUT_DIR_NAME).apply {
                if (isFile) {
                    delete()
                }
                if (!exists() && !mkdirs()) {
                    LogFacade.w(
                        LogModule.PLAYER,
                        TAG,
                        "failed to create shader directory: $absolutePath"
                    )
                    return null
                }
            }
        return directory
    }

    private fun ensureShaderFile(
        context: Context,
        directory: File,
        filename: String
    ): File? {
        val target = File(directory, filename)
        if (target.exists() && target.length() > 0L) {
            return target
        }

        val assetPath = "$ASSET_SHADER_DIR/$filename"
        return runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target
        }.onSuccess {
            LogFacade.i(
                LogModule.PLAYER,
                TAG,
                "shader copied: $assetPath -> ${target.absolutePath}"
            )
        }.onFailure { error ->
            target.delete()
            LogFacade.e(
                LogModule.PLAYER,
                TAG,
                "copy shader failed: $assetPath, reason=${error.message}",
                throwable = error
            )
        }.getOrNull()
    }
}
