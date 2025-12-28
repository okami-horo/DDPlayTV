package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliVideoCodec
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.mpd.BilibiliMpdGenerator
import com.xyoye.common_component.bilibili.net.BilibiliHeaders
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.BilibiliStorageFile
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursor
import com.xyoye.data_component.data.bilibili.BilibiliHistoryItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Date

class BilibiliStorage(
    library: MediaLibraryEntity
) : AbstractStorage(library),
    PagedStorage {
    private val storageKey = BilibiliPlaybackPreferencesStore.storageKey(library)
    private val repository = BilibiliRepository(storageKey)

    private var historyCursor: BilibiliHistoryCursor? = null
    private var historyHasMore: Boolean = true

    override var state: PagedStorage.State = PagedStorage.State.IDLE

    override var rootUri: Uri = Uri.parse(library.url)

    override suspend fun getRootFile(): StorageFile? = BilibiliStorageFile.root(this)

    override suspend fun openFile(file: StorageFile): InputStream? = null

    override suspend fun openDirectory(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        if (file.filePath() == "/history/" && refresh) {
            reset()
        }
        directory = file
        directoryFiles = listFilesInternal(file, refresh)
        if (file.filePath() == "/history/") {
            state = if (historyHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }
        return directoryFiles
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> = listFilesInternal(file, refresh = false)

    private suspend fun listFilesInternal(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        return when (file.filePath()) {
            "/" -> listOf(BilibiliStorageFile.historyDirectory(this))
            "/history/" -> listHistory(refresh)
            else -> {
                if (file.filePath().startsWith("/history/") && file.isDirectory()) {
                    val bvid = file.filePath().removePrefix("/history/").removeSuffix("/").substringBefore("/")
                    if (bvid.isNotBlank()) {
                        listPagelist(file, bvid)
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        }
    }

    private suspend fun listHistory(refresh: Boolean): List<StorageFile> {
        if (refresh) {
            resetHistoryCursor()
        }

        val cursor = historyCursor
        val data =
            repository.historyCursor(
                max = cursor?.max?.takeIf { it > 0 },
                viewAt = cursor?.viewAt?.takeIf { it > 0 },
                business = cursor?.business?.takeIf { it.isNotBlank() },
                ps = 30,
                type = "archive",
            ).getOrThrow()

        val nextCursor = data.cursor
        val rawList = data.list

        historyHasMore =
            rawList.isNotEmpty() &&
                nextCursor != null &&
                (cursor == null || nextCursor.max != cursor.max || nextCursor.viewAt != cursor.viewAt)

        historyCursor = nextCursor

        return rawList
            .mapNotNull { item -> mapHistoryItem(item) }
    }

    private fun mapHistoryItem(item: BilibiliHistoryItem): StorageFile? {
        val history = item.history ?: return null
        if (history.business != null && history.business != "archive") return null
        val bvid = history.bvid
        val cid = history.cid
        if (bvid.isBlank() || cid <= 0) return null

        val cover = item.cover
        val durationMs = item.durationSec.coerceAtLeast(0) * 1000

        return if (item.videos > 1) {
            BilibiliStorageFile.archiveDirectory(
                storage = this,
                bvid = bvid,
                title = item.title.ifBlank { bvid },
                coverUrl = cover,
                childCount = item.videos,
                payload = item,
            )
        } else {
            val title =
                buildString {
                    append(item.title.ifBlank { bvid })
                    history.part?.takeIf { it.isNotBlank() }?.let {
                        if (it != item.title) {
                            append(" - ")
                            append(it)
                        }
                    }
                }
            BilibiliStorageFile.archivePartFile(
                storage = this,
                bvid = bvid,
                cid = cid,
                title = title,
                coverUrl = cover,
                durationMs = durationMs,
                payload = item,
            )
        }
    }

    private suspend fun listPagelist(
        directory: StorageFile,
        bvid: String
    ): List<StorageFile> {
        val coverUrl = directory.fileCover()
        val historyItem = directory.getFile<BilibiliHistoryItem>()
        val baseTitle = historyItem?.title?.ifBlank { bvid } ?: directory.fileName().ifBlank { bvid }

        val pages =
            repository.pagelist(bvid).getOrThrow()

        return pages
            .filter { it.cid > 0 }
            .sortedBy { it.page }
            .map { page ->
                val title =
                    page.part?.takeIf { it.isNotBlank() }?.let { "$baseTitle - $it" }
                        ?: baseTitle
                BilibiliStorageFile.archivePartFile(
                    storage = this,
                    bvid = bvid,
                    cid = page.cid,
                    title = title,
                    coverUrl = coverUrl,
                    durationMs = page.duration.coerceAtLeast(0) * 1000,
                    payload = page,
                )
            }
    }

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? {
        val normalized =
            if (path.startsWith("/")) {
                path
            } else {
                "/$path"
            }
        return when {
            normalized == "/" -> BilibiliStorageFile.root(this)
            normalized == "/history/" -> BilibiliStorageFile.historyDirectory(this)
            normalized.startsWith("/history/") && isDirectory -> {
                val bvid = normalized.removePrefix("/history/").removeSuffix("/").substringBefore("/")
                if (bvid.isBlank()) return null
                BilibiliStorageFile.archiveDirectory(
                    storage = this,
                    bvid = bvid,
                    title = bvid,
                    coverUrl = null,
                    childCount = 0,
                    payload = null,
                )
            }

            normalized.startsWith("/history/") && !isDirectory -> {
                val segments = normalized.removePrefix("/history/").split("/")
                if (segments.size < 2) return null
                val bvid = segments[0]
                val cid = segments[1].toLongOrNull() ?: return null
                BilibiliStorageFile.archivePartFile(
                    storage = this,
                    bvid = bvid,
                    cid = cid,
                    title = cid.toString(),
                    coverUrl = null,
                    durationMs = 0,
                    payload = null,
                )
            }

            else -> null
        }
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? {
        val parsed = BilibiliKeys.parse(history.uniqueKey) ?: return null
        val cid = parsed.cid ?: return null
        val path = history.storagePath ?: "/history/${parsed.bvid}/$cid"
        return BilibiliStorageFile.archivePartFile(
            storage = this,
            bvid = parsed.bvid,
            cid = cid,
            title = history.videoName,
            coverUrl = null,
            durationMs = history.videoDuration,
            payload = null,
        ).also {
            it.playHistory = history.copy(storagePath = path, playTime = history.playTime.takeIf { it.time > 0 } ?: Date())
        }
    }

    override suspend fun createPlayUrl(file: StorageFile): String? {
        val parsed = BilibiliKeys.parse(file.uniqueKey()) ?: return null
        val cid = parsed.cid ?: return null
        val bvid = parsed.bvid

        val preferences = BilibiliPlaybackPreferencesStore.read(library)

        val primary = repository.playurl(bvid, cid, preferences)
        val primaryData = primary.getOrNull()
        val dash = primaryData?.dash

        if (dash != null && dash.video.isNotEmpty()) {
            val selectedVideo = selectVideo(dash.video, preferences.preferredQualityQn, preferences.preferredVideoCodec)
                ?: dash.video.first()
            val selectedAudio = dash.audio.maxByOrNull { it.bandwidth }

            val mpdFile =
                File(
                    PathHelper.getPlayCacheDirectory(),
                    "bilibili_${file.uniqueKey().toMd5String()}.mpd",
                )
            return withContext(Dispatchers.IO) {
                BilibiliMpdGenerator
                    .writeDashMpd(
                        outputFile = mpdFile,
                        dash = dash,
                        video = selectedVideo,
                        audio = selectedAudio,
                    ).absolutePath
            }
        }

        primaryData?.durl?.firstOrNull()?.url?.takeIf { it.isNotBlank() }?.let { return it }

        val fallback = repository.playurlFallbackOrNull(bvid, cid, preferences)
        fallback?.getOrNull()?.durl?.firstOrNull()?.url?.takeIf { it.isNotBlank() }?.let { return it }

        throw primary.exceptionOrNull() ?: BilibiliException.from(-1, "取流失败")
    }

    override fun getNetworkHeaders(): Map<String, String>? =
        BilibiliHeaders.withCookie(repository.cookieHeaderOrNull())

    override suspend fun test(): Boolean {
        val nav = repository.nav().getOrNull() ?: return false
        return nav.isLogin && repository.isLoggedIn()
    }

    override fun close() {
        // do nothing
    }

    fun isConnected(): Boolean = repository.isLoggedIn()

    fun resetHistoryCursor() {
        historyCursor = null
        historyHasMore = true
    }

    fun hasMoreHistory(): Boolean = historyHasMore

    suspend fun loadMoreHistory(): Result<List<StorageFile>> {
        if (directory?.filePath() != "/history/") {
            return Result.success(emptyList())
        }
        if (!historyHasMore) {
            return Result.success(emptyList())
        }
        return runCatching {
            listHistory(refresh = false)
        }
    }

    override fun hasMore(): Boolean = directory?.filePath() == "/history/" && historyHasMore

    override suspend fun reset() {
        resetHistoryCursor()
        state = PagedStorage.State.IDLE
    }

    override suspend fun loadMore(): Result<List<StorageFile>> {
        if (directory?.filePath() != "/history/") {
            return Result.success(emptyList())
        }
        if (!historyHasMore) {
            state = PagedStorage.State.NO_MORE
            return Result.success(emptyList())
        }
        state = PagedStorage.State.LOADING
        return runCatching {
            listHistory(refresh = false)
        }.onSuccess {
            state = if (historyHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }.onFailure {
            state = PagedStorage.State.ERROR
        }
    }

    private fun selectVideo(
        candidates: List<com.xyoye.data_component.data.bilibili.BilibiliDashMediaData>,
        preferredQn: Int,
        preferredCodec: BilibiliVideoCodec,
    ): com.xyoye.data_component.data.bilibili.BilibiliDashMediaData? {
        val byCodec =
            if (preferredCodec != BilibiliVideoCodec.AUTO && preferredCodec.codecid != null) {
                candidates.filter { it.codecid == preferredCodec.codecid }.ifEmpty { candidates }
            } else {
                candidates
            }

        if (preferredQn <= 0) {
            return byCodec.maxByOrNull { it.bandwidth }
        }

        val exact = byCodec.firstOrNull { it.id == preferredQn }
        if (exact != null) return exact

        val lowerOrEqual = byCodec.filter { it.id <= preferredQn }
        return (lowerOrEqual.ifEmpty { byCodec }).maxByOrNull { it.id }
    }
}
