package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.utils.PathHelper
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

/** Serializes MPD publication with retirement so a late response cannot recreate it. */
internal class BilibiliSessionManifest(
    private val directory: () -> File = { PathHelper.getPlayCacheDirectory() }
) {
    private val name = "bilibili_${UUID.randomUUID()}.mpd"
    private var file: File? = null
    private var closed = false

    @Synchronized
    fun write(writer: (File) -> Unit): String {
        if (closed) throw CancellationException("Playback manifest retired")
        val target = file ?: File(directory(), name).also { file = it }
        writer(target)
        return target.absolutePath
    }

    @Synchronized
    fun close() {
        closed = true
        file?.delete()
    }
}
