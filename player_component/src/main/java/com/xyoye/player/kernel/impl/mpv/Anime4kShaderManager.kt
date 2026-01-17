package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.player.kernel.anime4k.Anime4kMode
import com.xyoye.player.kernel.anime4k.Anime4kShaderAssets
import java.io.File
import java.io.FileOutputStream

internal object Anime4kShaderManager {
    private const val TAG = "Anime4kShaderManager"
    private const val ASSET_SHADER_DIR = Anime4kShaderAssets.ASSET_DIR
    private const val OUTPUT_DIR_NAME = "anime4k"

    const val MODE_OFF = Anime4kMode.MODE_OFF
    const val MODE_PERFORMANCE = Anime4kMode.MODE_PERFORMANCE
    const val MODE_QUALITY = Anime4kMode.MODE_QUALITY

    private val qualityShaders = Anime4kShaderAssets.qualityShaders.toTypedArray()
    private val performanceShaders = Anime4kShaderAssets.performanceShaders.toTypedArray()

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

        val directory = ensureShadersDirectory() ?: return emptyList()
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

    private fun ensureShadersDirectory(): File? {
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
                        "failed to create shader directory: $absolutePath",
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
                "shader copied: $assetPath -> ${target.absolutePath}",
            )
        }.onFailure { error ->
            target.delete()
            LogFacade.e(
                LogModule.PLAYER,
                TAG,
                "copy shader failed: $assetPath, reason=${error.message}",
                throwable = error,
            )
        }.getOrNull()
    }
}
