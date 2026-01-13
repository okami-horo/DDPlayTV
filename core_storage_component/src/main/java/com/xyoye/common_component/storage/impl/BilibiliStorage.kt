package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.net.BilibiliHeaders
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSessionStore
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.BilibiliStorageFile
import com.xyoye.common_component.storage.file.payloadAs
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursor
import com.xyoye.data_component.data.bilibili.BilibiliHistoryItem
import com.xyoye.data_component.data.bilibili.BilibiliLiveFollowItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.PlayerType
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
    private var historyState: PagedStorage.State = PagedStorage.State.IDLE

    private var followLivePage: Int = 1
    private var followLiveLoadedCount: Int = 0
    private var followLiveTotalLiveCount: Int = 0
    private var followLiveHasMore: Boolean = true
    private var followLiveState: PagedStorage.State = PagedStorage.State.IDLE

    override var state: PagedStorage.State
        get() =
            when (currentPagedDirectory()) {
                PagedDirectory.HISTORY -> historyState
                PagedDirectory.FOLLOW_LIVE -> followLiveState
                null -> PagedStorage.State.IDLE
            }
        set(value) {
            when (currentPagedDirectory()) {
                PagedDirectory.HISTORY -> historyState = value
                PagedDirectory.FOLLOW_LIVE -> followLiveState = value
                null -> Unit
            }
        }

    override var rootUri: Uri = Uri.parse(library.url)

    override suspend fun getRootFile(): StorageFile? = BilibiliStorageFile.root(this)

    override suspend fun openFile(file: StorageFile): InputStream? = null

    override suspend fun openDirectory(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        directory = file
        if (refresh) {
            resetCurrentPagingStateIfNeeded(file.filePath())
        }
        directoryFiles = listFilesInternal(file, refresh)
        syncStateToCurrentDirectory()
        return directoryFiles
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> = listFilesInternal(file, refresh = false)

    private suspend fun listFilesInternal(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> =
        when (file.filePath()) {
            PATH_ROOT ->
                listOf(
                    BilibiliStorageFile.historyDirectory(this),
                    BilibiliStorageFile.followLiveDirectory(this),
                )
            PATH_HISTORY_DIR -> listHistory(refresh)
            PATH_FOLLOW_LIVE_DIR -> listFollowLive(refresh)
            else -> {
                if (file.filePath().startsWith(PATH_HISTORY_DIR) && file.isDirectory()) {
                    val bvid =
                        file
                            .filePath()
                            .removePrefix(PATH_HISTORY_DIR)
                            .removeSuffix("/")
                            .substringBefore("/")
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

    private suspend fun listHistory(refresh: Boolean): List<StorageFile> {
        if (refresh) {
            resetHistoryCursor()
        }

        val mapped = mutableListOf<StorageFile>()
        var attempts = 0

        while (mapped.isEmpty() && historyHasMore && attempts < 5) {
            val cursor = historyCursor
            val data =
                repository
                    .historyCursor(
                        max = cursor?.max?.takeIf { it > 0 },
                        viewAt = cursor?.viewAt?.takeIf { it > 0 },
                        business = cursor?.business?.takeIf { it.isNotBlank() },
                        ps = 30,
                        type = "all",
                        preferCache = !refresh,
                    ).getOrThrow()

            val nextCursor = data.cursor
            val rawList = data.list

            historyHasMore =
                rawList.isNotEmpty() &&
                nextCursor != null &&
                (cursor == null || nextCursor.max != cursor.max || nextCursor.viewAt != cursor.viewAt)

            historyCursor = nextCursor

            mapped.addAll(rawList.mapNotNull { item -> mapHistoryItem(item) })
            attempts++
        }

        return mapped
    }

    private suspend fun listFollowLive(refresh: Boolean): List<StorageFile> {
        if (refresh) {
            resetFollowLivePaging()
        }

        val mapped = mutableListOf<StorageFile>()
        var attempts = 0

        while (mapped.isEmpty() && followLiveHasMore && attempts < MAX_EMPTY_PAGE_ATTEMPTS) {
            val currentPage = followLivePage
            val data =
                repository
                    .liveFollow(
                        page = currentPage,
                        pageSize = FOLLOW_LIVE_PAGE_SIZE,
                        ignoreRecord = 1,
                        hitAb = true,
                    ).getOrThrow()

            followLivePage = currentPage + 1

            followLiveTotalLiveCount = data.liveCount.coerceAtLeast(0)

            val pageMapped =
                data.list
                    .asSequence()
                    .filter { it.liveStatus == 1 }
                    .mapNotNull(::mapFollowLiveItem)
                    .toList()

            mapped.addAll(pageMapped)
            followLiveLoadedCount += pageMapped.size

            followLiveHasMore =
                followLiveTotalLiveCount > 0 &&
                    followLiveLoadedCount < followLiveTotalLiveCount &&
                    (data.totalPage <= 0 || followLivePage <= data.totalPage)

            attempts++
        }

        if (mapped.isEmpty() && attempts >= MAX_EMPTY_PAGE_ATTEMPTS) {
            followLiveHasMore = false
        }

        return mapped
    }

    private fun mapHistoryItem(item: BilibiliHistoryItem): StorageFile? {
        val history = item.history ?: return null

        val business = history.business?.takeIf { it.isNotBlank() } ?: "archive"
        val cover = item.cover
        val durationMs = item.durationSec.coerceAtLeast(0) * 1000

        when (business) {
            "live" -> {
                val roomId = history.oid.takeIf { it > 0 } ?: return null
                return BilibiliStorageFile.liveRoomFile(
                    storage = this,
                    roomId = roomId,
                    title = item.title.ifBlank { roomId.toString() },
                    coverUrl = cover,
                    payload = item,
                )
            }

            "pgc" -> {
                val epId = history.epid.takeIf { it > 0 } ?: return null
                val cid = history.cid.takeIf { it > 0 } ?: return null
                val avid = history.oid.takeIf { it > 0 }
                val seasonId = item.kid.takeIf { it > 0 }

                val episodeTitle =
                    item.showTitle?.takeIf { it.isNotBlank() }
                        ?: item.longTitle?.takeIf { it.isNotBlank() }

                val title =
                    buildString {
                        append(item.title.ifBlank { epId.toString() })
                        episodeTitle?.let {
                            if (it != item.title) {
                                append(" - ")
                                append(it)
                            }
                        }
                    }

                return BilibiliStorageFile.pgcEpisodeFile(
                    storage = this,
                    seasonId = seasonId,
                    epId = epId,
                    cid = cid,
                    avid = avid,
                    title = title,
                    coverUrl = cover,
                    durationMs = durationMs,
                    payload = item,
                )
            }

            "archive" -> {
                val bvid = history.bvid
                val cid = history.cid
                if (bvid.isBlank() || cid <= 0) return null

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

            else -> return null
        }
    }

    private fun mapFollowLiveItem(item: BilibiliLiveFollowItem): StorageFile? {
        val roomId = item.roomId.takeIf { it > 0 } ?: return null
        if (item.liveStatus != 1) return null

        val uname = item.uname.takeIf { it.isNotBlank() }
        val liveTitle = item.title.takeIf { it.isNotBlank() }
        val title =
            when {
                uname != null && liveTitle != null -> "$uname - $liveTitle"
                uname != null -> uname
                liveTitle != null -> liveTitle
                else -> roomId.toString()
            }

        val cover =
            item.roomCover?.takeIf { it.isNotBlank() }
                ?: item.face?.takeIf { it.isNotBlank() }

        return BilibiliStorageFile.followLiveRoomFile(
            storage = this,
            roomId = roomId,
            title = title,
            coverUrl = cover,
            payload = item,
        )
    }

    private suspend fun listPagelist(
        directory: StorageFile,
        bvid: String
    ): List<StorageFile> {
        val coverUrl = directory.fileCover()
        val remoteHistoryItem = directory.payloadAs<BilibiliHistoryItem>()
        val remoteHistoryCid = remoteHistoryItem?.history?.cid?.takeIf { it > 0 }
        val baseTitle = remoteHistoryItem?.title?.ifBlank { bvid } ?: directory.fileName().ifBlank { bvid }

        val pages =
            repository.pagelist(bvid).getOrThrow()

        return pages
            .filter { it.cid > 0 }
            .sortedBy { it.page }
            .map { page ->
                val title =
                    page.part?.takeIf { it.isNotBlank() }?.let { "$baseTitle - $it" }
                        ?: baseTitle
                val payload: Any? =
                    if (remoteHistoryCid != null && remoteHistoryCid == page.cid) {
                        remoteHistoryItem
                    } else {
                        page
                    }
                BilibiliStorageFile.archivePartFile(
                    storage = this,
                    bvid = bvid,
                    cid = page.cid,
                    title = title,
                    coverUrl = coverUrl,
                    durationMs = page.duration.coerceAtLeast(0) * 1000,
                    payload = payload,
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
            normalized == PATH_ROOT -> BilibiliStorageFile.root(this)
            normalized == PATH_HISTORY_DIR -> BilibiliStorageFile.historyDirectory(this)
            normalized == PATH_FOLLOW_LIVE_DIR -> BilibiliStorageFile.followLiveDirectory(this)
            normalized.startsWith(PATH_HISTORY_LIVE_PREFIX) && !isDirectory -> {
                val roomId = normalized.removePrefix(PATH_HISTORY_LIVE_PREFIX).trim('/').toLongOrNull() ?: return null
                if (roomId <= 0) return null
                BilibiliStorageFile.liveRoomFile(
                    storage = this,
                    roomId = roomId,
                    title = roomId.toString(),
                    coverUrl = null,
                    payload = null,
                )
            }

            normalized.startsWith(PATH_FOLLOW_LIVE_PREFIX) && !isDirectory -> {
                val roomId = normalized.removePrefix(PATH_FOLLOW_LIVE_PREFIX).trim('/').toLongOrNull() ?: return null
                if (roomId <= 0) return null
                BilibiliStorageFile.followLiveRoomFile(
                    storage = this,
                    roomId = roomId,
                    title = roomId.toString(),
                    coverUrl = null,
                    payload = null,
                )
            }

            normalized.startsWith(PATH_HISTORY_DIR) && isDirectory -> {
                val bvid = normalized.removePrefix(PATH_HISTORY_DIR).removeSuffix("/").substringBefore("/")
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

            normalized.startsWith(PATH_HISTORY_DIR) && !isDirectory -> {
                val segments = normalized.removePrefix(PATH_HISTORY_DIR).split("/")
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
        return when (parsed) {
            is BilibiliKeys.ArchiveKey -> {
                val cid = parsed.cid ?: return null
                val path = history.storagePath ?: "/history/${parsed.bvid}/$cid"
                BilibiliStorageFile
                    .archivePartFile(
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

            is BilibiliKeys.LiveKey -> {
                val roomId = parsed.roomId
                val resolvedPath =
                    when {
                        history.storagePath?.startsWith(PATH_FOLLOW_LIVE_PREFIX) == true -> "$PATH_FOLLOW_LIVE_PREFIX$roomId"
                        history.storagePath?.startsWith(PATH_HISTORY_LIVE_PREFIX) == true -> "$PATH_HISTORY_LIVE_PREFIX$roomId"
                        else -> "$PATH_HISTORY_LIVE_PREFIX$roomId"
                    }

                val file =
                    if (resolvedPath.startsWith(PATH_FOLLOW_LIVE_PREFIX)) {
                        BilibiliStorageFile.followLiveRoomFile(
                            storage = this,
                            roomId = roomId,
                            title = history.videoName,
                            coverUrl = null,
                            payload = null,
                        )
                    } else {
                        BilibiliStorageFile.liveRoomFile(
                            storage = this,
                            roomId = roomId,
                            title = history.videoName,
                            coverUrl = null,
                            payload = null,
                        )
                    }

                file.also {
                    it.playHistory =
                        history.copy(
                            storagePath = resolvedPath,
                            playTime = history.playTime.takeIf { it.time > 0 } ?: Date(),
                        )
                }
            }

            is BilibiliKeys.PgcEpisodeKey -> {
                val path = history.storagePath ?: "/history/pgc/${parsed.seasonId ?: 0}/${parsed.epId}"
                BilibiliStorageFile
                    .pgcEpisodeFile(
                        storage = this,
                        seasonId = parsed.seasonId,
                        epId = parsed.epId,
                        cid = parsed.cid,
                        avid = parsed.avid,
                        title = history.videoName,
                        coverUrl = null,
                        durationMs = history.videoDuration,
                        payload = null,
                    ).also {
                        it.playHistory = history.copy(storagePath = path, playTime = history.playTime.takeIf { it.time > 0 } ?: Date())
                    }
            }

            is BilibiliKeys.PgcSeasonKey -> null
        }
    }

    override suspend fun createPlayUrl(file: StorageFile): String? {
        val parsed = BilibiliKeys.parse(file.uniqueKey()) ?: return null
        try {
            if (parsed is BilibiliKeys.LiveKey) {
                val info = repository.liveRoomInfo(parsed.roomId).getOrThrow()
                val roomId = info.roomId.takeIf { it > 0 } ?: parsed.roomId
                val playUrl = repository.livePlayUrl(roomId).getOrThrow()
                playUrl.durl
                    .firstOrNull()
                    ?.url
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                throw BilibiliException.from(-1, "取流失败")
            }

            val session =
                BilibiliPlaybackSession(
                    storageId = library.id,
                    uniqueKey = file.uniqueKey(),
                    storageKey = storageKey,
                    repository = repository,
                    key = parsed,
                )
            BilibiliPlaybackSessionStore.put(session)
            return session
                .prepare()
                .onFailure {
                    BilibiliPlaybackSessionStore.remove(library.id, file.uniqueKey())
                }.getOrThrow()
        } catch (t: Throwable) {
            val extraInfo =
                "storageId=${library.id}, uniqueKey=${file.uniqueKey()}, filePath=${file.filePath()}, parsedType=${parsed::class.java.simpleName}"
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "BilibiliStorage",
                "createPlayUrl",
                extraInfo,
            )
            throw t
        }
    }

    override fun supportedPlayerTypes(): Set<PlayerType> =
        setOf(
            PlayerType.TYPE_EXO_PLAYER,
            PlayerType.TYPE_VLC_PLAYER,
            PlayerType.TYPE_MPV_PLAYER,
        )

    override fun preferredPlayerType(): PlayerType = PlayerType.TYPE_EXO_PLAYER

    override fun getNetworkHeaders(): Map<String, String>? = BilibiliHeaders.withCookie(repository.cookieHeaderOrNull())

    override fun getNetworkHeaders(file: StorageFile): Map<String, String>? {
        val headers = getNetworkHeaders() ?: return null
        val bilibiliKey = BilibiliKeys.parse(file.uniqueKey()) ?: return headers

        val referer =
            when (bilibiliKey) {
                is BilibiliKeys.ArchiveKey -> "https://www.bilibili.com/video/${bilibiliKey.bvid}"
                is BilibiliKeys.PgcEpisodeKey -> "https://www.bilibili.com/bangumi/play/ep${bilibiliKey.epId}"
                is BilibiliKeys.LiveKey -> "https://live.bilibili.com/${bilibiliKey.roomId}"
                is BilibiliKeys.PgcSeasonKey -> null
            } ?: return headers

        return headers.toMutableMap().apply {
            this[BilibiliHeaders.HEADER_REFERER] = referer
        }
    }

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
        historyState = PagedStorage.State.IDLE
    }

    fun hasMoreHistory(): Boolean = historyHasMore

    suspend fun loadMoreHistory(): Result<List<StorageFile>> {
        if (directory?.filePath() != PATH_HISTORY_DIR) {
            return Result.success(emptyList())
        }
        if (!historyHasMore) {
            return Result.success(emptyList())
        }
        return runCatching {
            listHistory(refresh = false)
        }
    }

    fun resetFollowLivePaging() {
        followLivePage = 1
        followLiveLoadedCount = 0
        followLiveTotalLiveCount = 0
        followLiveHasMore = true
        followLiveState = PagedStorage.State.IDLE
    }

    override fun shouldShowPagingItem(directory: StorageFile?): Boolean =
        isBilibiliPagedDirectoryPath(directory?.filePath())

    override fun hasMore(): Boolean =
        when (directory?.filePath()) {
            PATH_HISTORY_DIR -> historyHasMore
            PATH_FOLLOW_LIVE_DIR -> followLiveHasMore
            else -> false
        }

    override suspend fun reset() {
        when (directory?.filePath()) {
            PATH_HISTORY_DIR -> resetHistoryCursor()
            PATH_FOLLOW_LIVE_DIR -> resetFollowLivePaging()
            else -> {
                resetHistoryCursor()
                resetFollowLivePaging()
            }
        }
        syncStateToCurrentDirectory()
    }

    override suspend fun loadMore(): Result<List<StorageFile>> =
        when (directory?.filePath()) {
            PATH_HISTORY_DIR -> loadMoreHistoryInternal()
            PATH_FOLLOW_LIVE_DIR -> loadMoreFollowLiveInternal()
            else -> Result.success(emptyList())
        }

    private suspend fun loadMoreHistoryInternal(): Result<List<StorageFile>> {
        if (!historyHasMore) {
            historyState = PagedStorage.State.NO_MORE
            return Result.success(emptyList())
        }
        historyState = PagedStorage.State.LOADING
        return runCatching {
            listHistory(refresh = false)
        }.onSuccess {
            historyState = if (historyHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }.onFailure {
            historyState = PagedStorage.State.ERROR
        }
    }

    private suspend fun loadMoreFollowLiveInternal(): Result<List<StorageFile>> {
        if (!followLiveHasMore) {
            followLiveState = PagedStorage.State.NO_MORE
            return Result.success(emptyList())
        }
        followLiveState = PagedStorage.State.LOADING
        return runCatching {
            listFollowLive(refresh = false)
        }.onSuccess {
            followLiveState = if (followLiveHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }.onFailure {
            followLiveState = PagedStorage.State.ERROR
        }
    }

    private fun syncStateToCurrentDirectory() {
        when (directory?.filePath()) {
            PATH_HISTORY_DIR -> historyState = if (historyHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
            PATH_FOLLOW_LIVE_DIR -> followLiveState = if (followLiveHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
            else -> Unit
        }
    }

    private fun resetCurrentPagingStateIfNeeded(path: String) {
        when (path) {
            PATH_HISTORY_DIR -> resetHistoryCursor()
            PATH_FOLLOW_LIVE_DIR -> resetFollowLivePaging()
            else -> Unit
        }
    }

    private fun currentPagedDirectory(): PagedDirectory? =
        when (directory?.filePath()) {
            PATH_HISTORY_DIR -> PagedDirectory.HISTORY
            PATH_FOLLOW_LIVE_DIR -> PagedDirectory.FOLLOW_LIVE
            else -> null
        }

    private enum class PagedDirectory {
        HISTORY,
        FOLLOW_LIVE,
    }

    companion object {
        const val PATH_ROOT: String = "/"
        const val PATH_HISTORY_DIR: String = "/history/"
        const val PATH_FOLLOW_LIVE_DIR: String = "/follow_live/"
        const val PATH_HISTORY_LIVE_PREFIX: String = "/history/live/"
        const val PATH_FOLLOW_LIVE_PREFIX: String = "/follow_live/"

        private const val MAX_EMPTY_PAGE_ATTEMPTS: Int = 5
        private const val FOLLOW_LIVE_PAGE_SIZE: Int = 9

        fun isBilibiliPagedDirectoryPath(path: String?): Boolean =
            path == PATH_HISTORY_DIR || path == PATH_FOLLOW_LIVE_DIR
    }
}
