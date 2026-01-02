package com.xyoye.open_cc

import java.io.File

/**
 * Created by xyoye on 2023/5/27
 */

object OpenCC {
    init {
        System.loadLibrary("open_cc")
    }

    private external fun convert(
        text: String,
        configJsonPath: String
    ): String

    fun convertSC(text: String): String {
        val config = OpenCCFile.t2s
        if (config.exists().not()) {
            return text
        }

        return convertWithConfigFile(text, config)
    }

    fun convertTC(text: String): String {
        val config = OpenCCFile.s2t
        if (config.exists().not()) {
            return text
        }

        return convertWithConfigFile(text, config)
    }

    private fun convertWithConfigFile(
        text: String,
        config: File
    ): String =
        try {
            convertCompat(text, config.absolutePath)
        } catch (t: Throwable) {
            com.xyoye.common_component.utils.ErrorReportHelper.postCatchedException(
                t,
                "OpenCC",
                "Failed to convert text with config: ${config.absolutePath}",
            )
            text
        }

    /**
     * `libopen_cc.so` uses JNI string conversion and expects valid standard UTF-8 input.
     *
     * Some characters (e.g. `\u0000` or non-BMP code points like emoji) may be encoded as
     * *modified UTF-8* across JNI, which is invalid for OpenCC and can trigger a native
     * abort (SIGABRT) due to an uncaught C++ exception.
     *
     * To avoid crashing, we split the text and only feed "safe" segments to OpenCC, while
     * keeping other characters unchanged.
     */
    private fun convertCompat(
        text: String,
        configJsonPath: String
    ): String {
        if (text.isEmpty()) {
            return text
        }

        if (containsJniModifiedUtf8IncompatibleChars(text).not()) {
            return convert(text, configJsonPath)
        }

        val output = StringBuilder(text.length)
        val safeSegment = StringBuilder()

        fun flushSafeSegment() {
            if (safeSegment.isEmpty()) return

            val segment = safeSegment.toString()
            safeSegment.setLength(0)
            output.append(convertSegmentOrFallback(segment, configJsonPath))
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]

            if (char == '\u0000') {
                flushSafeSegment()
                output.append(char)
                index++
                continue
            }

            if (Character.isHighSurrogate(char)) {
                flushSafeSegment()

                if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                    val codePoint = Character.toCodePoint(char, text[index + 1])
                    output.appendCodePoint(codePoint)
                    index += 2
                } else {
                    output.append(char)
                    index++
                }
                continue
            }

            if (Character.isLowSurrogate(char)) {
                flushSafeSegment()
                output.append(char)
                index++
                continue
            }

            safeSegment.append(char)
            index++
        }

        flushSafeSegment()
        return output.toString()
    }

    private fun containsJniModifiedUtf8IncompatibleChars(text: String): Boolean {
        for (i in text.indices) {
            val c = text[i]
            if (c == '\u0000' || Character.isSurrogate(c)) {
                return true
            }
        }
        return false
    }

    private fun convertSegmentOrFallback(
        text: String,
        configJsonPath: String
    ): String =
        try {
            convert(text, configJsonPath)
        } catch (t: Throwable) {
            com.xyoye.common_component.utils.ErrorReportHelper.postCatchedException(
                t,
                "OpenCC",
                "Failed to convert text segment with config: $configJsonPath",
            )
            text
        }
}
