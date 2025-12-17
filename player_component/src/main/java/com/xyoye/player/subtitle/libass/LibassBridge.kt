package com.xyoye.player.subtitle.libass

/**
 * CPU libass 渲染路径已废弃。保留类仅为避免历史引用编译错误，
 * 任意调用都会抛出异常，请改用 GPU 渲染管线（AssGpuRenderer）。
 */
@Deprecated(
    message = "CPU libass renderer removed; use AssGpuRenderer GPU pipeline.",
    level = DeprecationLevel.ERROR,
)
class LibassBridge {
    companion object {
        init {
            System.loadLibrary("libass_bridge")
        }
    }

    private fun unsupported(): Nothing = error("CPU libass renderer has been removed; use AssGpuRenderer GPU pipeline.")

    fun isReady(): Boolean = false

    fun release(): Unit = unsupported()

    fun setFrameSize(
        width: Int,
        height: Int
    ): Unit = unsupported()

    fun setFonts(
        defaultFont: String?,
        fontDirectories: List<String>
    ): Unit = unsupported()

    fun loadTrack(path: String): Boolean = unsupported()

    fun render(
        timeMs: Long,
        bitmap: android.graphics.Bitmap
    ): Boolean = unsupported()

    fun setGlobalOpacity(percent: Int): Unit = unsupported()
}
