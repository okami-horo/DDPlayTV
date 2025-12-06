package com.xyoye.common_component.subtitle

import android.content.Context
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.weight.ToastCenter
import java.io.File
import java.io.FileOutputStream

object SubtitleFontManager {
    private const val TAG = "SubtitleFontManager"
    private const val FONT_DIR_NAME = "fonts"
    private const val ASSET_FONT_DIR = "fonts"

    const val DEFAULT_FONT_FILE = "NotoSansCJKsc-Regular.otf"
    const val DEFAULT_FONT_FAMILY = "Noto Sans CJK SC"

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (!initialized) {
                ensureDefaultFont(context.applicationContext)
                initialized = true
            }
        }
    }

    fun ensureDefaultFont(context: Context): Boolean {
        val directory = ensureFontsDirectory(context)
        val targetFile = File(directory, DEFAULT_FONT_FILE)
        if (targetFile.exists() && targetFile.length() > 0L) {
            if (!initialized) {
                LogFacade.i(LogModule.SUBTITLE, TAG, "default font already present: ${targetFile.absolutePath}")
            }
            return true
        }
        val result = copyDefaultFontFromAssets(context, targetFile)
        if (result) {
            LogFacade.i(LogModule.SUBTITLE, TAG, "default font copied to ${targetFile.absolutePath}")
        }
        return result
    }

    fun getFontsDirectoryPath(context: Context): String? {
        val directory = ensureFontsDirectory(context)
        return if (directory.exists() && directory.isDirectory) {
            directory.absolutePath
        } else {
            null
        }
    }

    fun getDefaultFontPath(context: Context): String? {
        val directory = ensureFontsDirectory(context)
        val targetFile = File(directory, DEFAULT_FONT_FILE)
        val fontAvailable = targetFile.exists() && targetFile.length() > 0L
        if (!fontAvailable && !ensureDefaultFont(context)) {
            LogFacade.e(LogModule.SUBTITLE, TAG, "default font unavailable: ${targetFile.absolutePath}")
            return null
        }
        return targetFile.absolutePath
    }

    private fun ensureFontsDirectory(context: Context): File {
        val directory = File(context.filesDir, FONT_DIR_NAME)
        if (!directory.exists()) {
            val created = runCatching { directory.mkdirs() }.getOrDefault(false)
            if (created) {
                LogFacade.i(LogModule.SUBTITLE, TAG, "created font directory: ${directory.absolutePath}")
            } else if (!directory.exists()) {
                LogFacade.e(LogModule.SUBTITLE, TAG, "failed to create font directory: ${directory.absolutePath}")
            }
        }
        return directory
    }

    private fun copyDefaultFontFromAssets(context: Context, target: File): Boolean {
        val assetPath = "$ASSET_FONT_DIR/$DEFAULT_FONT_FILE"
        return runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.onFailure {
            target.delete()
            LogFacade.e(LogModule.SUBTITLE, TAG, "copy default font failed: ${it.message}")
            ToastCenter.showError("默认字幕字体初始化失败，字幕可能缺字")
        }.getOrDefault(false)
    }
}
