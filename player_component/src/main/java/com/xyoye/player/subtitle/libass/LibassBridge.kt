package com.xyoye.player.subtitle.libass

import android.graphics.Bitmap

class LibassBridge {

    companion object {
        init {
            System.loadLibrary("libass_bridge")
        }
    }

    private var handle: Long = nativeCreate()

    fun isReady() = handle != 0L

    fun release() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    fun setFrameSize(width: Int, height: Int) {
        if (handle != 0L) {
            nativeSetFrameSize(handle, width, height)
        }
    }

    fun setFonts(defaultFont: String?, fontDirectories: List<String>) {
        if (handle != 0L) {
            nativeSetFonts(handle, defaultFont, fontDirectories.toTypedArray())
        }
    }

    fun loadTrack(path: String): Boolean {
        if (handle == 0L) return false
        return nativeLoadTrack(handle, path)
    }

    fun render(timeMs: Long, bitmap: Bitmap): Boolean {
        if (handle == 0L) return false
        return nativeRenderFrame(handle, timeMs, bitmap)
    }

    fun setGlobalOpacity(percent: Int) {
        if (handle != 0L) {
            nativeSetGlobalOpacity(handle, percent)
        }
    }

    private external fun nativeCreate(): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeSetFrameSize(handle: Long, width: Int, height: Int)

    private external fun nativeSetFonts(handle: Long, defaultFont: String?, directories: Array<String>)

    private external fun nativeLoadTrack(handle: Long, path: String): Boolean

    private external fun nativeRenderFrame(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean

    private external fun nativeSetGlobalOpacity(handle: Long, percent: Int)
}
