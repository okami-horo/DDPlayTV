package com.xyoye.open_cc

import java.io.File

/**
 * Created by xyoye on 2023/5/27
 */

object OpenCC {

    private val isNativeLibraryLoaded: Boolean = try {
        System.loadLibrary("open_cc")
        true
    } catch (error: Throwable) {
        android.util.Log.w(
            "OpenCC",
            "Failed to load native open_cc library, simplified/traditional conversion will be disabled",
            error
        )
        false
    }

    private external fun convert(text: String, configJsonPath: String): String

    fun convertSC(text: String): String {
        if (!isNativeLibraryLoaded) {
            return text
        }

        val config = OpenCCFile.t2s
        if (config.exists().not()) {
            return text
        }

        return convert(text, config)
    }

    fun convertTC(text: String): String {
        if (!isNativeLibraryLoaded) {
            return text
        }

        val config = OpenCCFile.s2t
        if (config.exists().not()) {
            return text
        }

        return convert(text, config)
    }

    private fun convert(text: String, config: File): String {
        if (!isNativeLibraryLoaded) {
            return text
        }

        return try {
            convert(text, config.absolutePath)
        } catch (t: Throwable) {
            com.xyoye.common_component.utils.ErrorReportHelper.postCatchedException(
                t, "OpenCC", "Failed to convert text with config: ${config.absolutePath}"
            )
            text
        }
    }
}