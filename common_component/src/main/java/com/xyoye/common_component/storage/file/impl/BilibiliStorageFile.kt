package com.xyoye.common_component.storage.file.impl

import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.PlayHistoryEntity

class BilibiliStorageFile(
    override var storage: Storage,
    private val path: String,
    private val name: String,
    private val isDir: Boolean,
    private val uniqueKey: String,
    private val coverUrl: String? = null,
    private val durationMs: Long = 0L,
    private val childCount: Int = 0,
    private val payload: Any? = null,
    private val playable: Boolean = false,
) : StorageFile {
    override var playHistory: PlayHistoryEntity? = null

    override fun filePath(): String = path

    override fun fileUrl(): String = path

    override fun fileCover(): String? = coverUrl

    override fun storagePath(): String = path

    override fun isDirectory(): Boolean = isDir

    override fun isFile(): Boolean = !isDir

    override fun fileName(): String = name

    override fun fileLength(): Long = 0L

    override fun uniqueKey(): String = uniqueKey

    override fun isRootFile(): Boolean = path == "/"

    override fun canRead(): Boolean = true

    override fun childFileCount(): Int = childCount

    override fun <T> getFile(): T? = payload as? T

    override fun clone(): StorageFile =
        BilibiliStorageFile(
            storage = storage,
            path = path,
            name = name,
            isDir = isDir,
            uniqueKey = uniqueKey,
            coverUrl = coverUrl,
            durationMs = durationMs,
            childCount = childCount,
            payload = payload,
            playable = playable,
        ).also {
            it.playHistory = playHistory
        }

    override fun isVideoFile(): Boolean = playable

    override fun isStoragePathParent(childPath: String): Boolean = childPath.startsWith(storagePath())

    override fun close() {
        // do nothing
    }

    override fun videoDuration(): Long = durationMs

    companion object {
        fun root(storage: Storage): BilibiliStorageFile =
            BilibiliStorageFile(
                storage = storage,
                path = "/",
                name = "根目录",
                isDir = true,
                uniqueKey = "bilibili://root",
            )

        fun historyDirectory(storage: Storage): BilibiliStorageFile =
            BilibiliStorageFile(
                storage = storage,
                path = "/history/",
                name = "历史记录",
                isDir = true,
                uniqueKey = "bilibili://dir/history",
            )

        fun archiveDirectory(
            storage: Storage,
            bvid: String,
            title: String,
            coverUrl: String?,
            childCount: Int,
            payload: Any?,
        ): BilibiliStorageFile =
            BilibiliStorageFile(
                storage = storage,
                path = "/history/$bvid/",
                name = title,
                isDir = true,
                uniqueKey = BilibiliKeys.archiveDirectoryKey(bvid),
                coverUrl = coverUrl,
                childCount = childCount,
                payload = payload,
            )

        fun archivePartFile(
            storage: Storage,
            bvid: String,
            cid: Long,
            title: String,
            coverUrl: String?,
            durationMs: Long,
            payload: Any?,
        ): BilibiliStorageFile =
            BilibiliStorageFile(
                storage = storage,
                path = "/history/$bvid/$cid",
                name = title,
                isDir = false,
                uniqueKey = BilibiliKeys.archivePartKey(bvid, cid),
                coverUrl = coverUrl,
                durationMs = durationMs,
                payload = payload,
                playable = true,
            )
    }
}

