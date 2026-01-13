package com.xyoye.common_component.storage.impl

import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.network.repository.BaiduPanRepository
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanReAuthRequiredException
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanTokenManager
import com.xyoye.common_component.storage.baidupan.play.BaiduPanDlinkCache
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.common_component.storage.file.impl.BaiduPanStorageFile
import com.xyoye.common_component.storage.file.payloadAs
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanFileItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.TimeUnit

class BaiduPanStorage(
    library: MediaLibraryEntity
) : AbstractStorage(library),
    PagedStorage,
    AuthStorage {
    private val rangeUnsupportedRefreshLock = Any()

    private val storageKey = BaiduPanAuthStore.storageKey(library)
    private val repository = BaiduPanRepository(storageKey)
    private val tokenManager = BaiduPanTokenManager(storageKey)
    private val dlinkCache = BaiduPanDlinkCache()

    private var pagingDir: String? = null
    private var pagingStart: Int = 0
    private var pagingHasMore: Boolean = true

    override var state: PagedStorage.State = PagedStorage.State.IDLE

    override fun isConnected(): Boolean = repository.isAuthorized()

    override fun requiresLogin(directory: StorageFile?): Boolean = !isConnected()

    override fun loginActionText(directory: StorageFile?): String = "扫码授权"

    override suspend fun getRootFile(): StorageFile? {
        if (!repository.isAuthorized()) {
            throw BaiduPanReAuthRequiredException("请先扫码授权")
        }

        // 校验授权态 + 刷新用户信息（便于后续 UI 展示/诊断）
        repository.xpanUinfo().getOrThrow()

        return BaiduPanStorageFile.root(this)
    }

    override suspend fun openDirectory(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        directory = file
        val dirPath = normalizePath(file.filePath())
        if (refresh || pagingDir != dirPath) {
            resetPaging(dirPath)
        }

        val response =
            repository.xpanList(
                dir = dirPath,
                start = 0,
                limit = DEFAULT_PAGE_LIMIT,
            ).getOrThrow()

        val items = response.list.orEmpty()
        pagingStart = items.size
        pagingHasMore = items.size >= DEFAULT_PAGE_LIMIT
        state = if (pagingHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE

        directoryFiles = items.map { BaiduPanStorageFile(it, this) }
        return directoryFiles
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> {
        val dirPath = normalizePath(file.filePath())
        val response =
            repository.xpanList(
                dir = dirPath,
                start = 0,
                limit = DEFAULT_PAGE_LIMIT,
            ).getOrThrow()
        return response.list.orEmpty().map { BaiduPanStorageFile(it, this) }
    }

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? {
        val normalized = normalizePath(path)
        if (normalized == "/" && isDirectory) {
            return getRootFile()
        }

        val parentDir = normalized.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }

        var start = 0
        while (true) {
            val response =
                repository.xpanList(
                    dir = parentDir,
                    start = start,
                    limit = DEFAULT_PAGE_LIMIT,
                ).getOrThrow()

            val items = response.list.orEmpty()
            val item =
                items.firstOrNull { it.path == normalized && it.isdir == (if (isDirectory) 1 else 0) }
            if (item != null) {
                return BaiduPanStorageFile(item, this)
            }

            if (items.size < DEFAULT_PAGE_LIMIT) {
                return null
            }
            start += items.size
        }
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? =
        history.storagePath
            ?.let { pathFile(it, isDirectory = false) }
            ?.also { it.playHistory = history }

    override suspend fun openFile(file: StorageFile): InputStream? {
        if (file.isDirectory()) {
            return null
        }

        val upstream = resolveUpstream(file, forceRefresh = false)
        return ResourceRepository
            .getResourceResponseBody(
                url = upstream.url,
                headers = getNetworkHeaders(file).orEmpty(),
            ).getOrNull()
            ?.byteStream()
    }

    override suspend fun createPlayUrl(file: StorageFile): String? =
        createPlayUrl(
            file = file,
            profile =
                PlaybackProfile(
                    playerType = PlayerType.valueOf(PlayerConfig.getUsePlayerType()),
                    source = PlaybackProfileSource.GLOBAL,
                ),
        )

    override suspend fun createPlayUrl(
        file: StorageFile,
        profile: PlaybackProfile
    ): String? {
        if (!file.isVideoFile()) {
            throw IllegalStateException("该文件不是视频，无法播放")
        }

        val upstream = resolveUpstream(file, forceRefresh = false)

        val playerType = profile.playerType
        if (playerType != PlayerType.TYPE_MPV_PLAYER && playerType != PlayerType.TYPE_VLC_PLAYER) {
            return upstream.url
        }

        val fileName = runCatching { file.fileName() }.getOrNull().orEmpty().ifEmpty { "video" }

        val (mode, interval) =
            when (playerType) {
                PlayerType.TYPE_MPV_PLAYER ->
                    PlayerConfig.getMpvLocalProxyMode() to PlayerConfig.getMpvProxyRangeMinIntervalMs().toLong()
                PlayerType.TYPE_VLC_PLAYER ->
                    PlayerConfig.getVlcLocalProxyMode() to PlayerConfig.getVlcProxyRangeMinIntervalMs().toLong()
                else -> return upstream.url
            }

        return LocalProxy.wrapIfNeeded(
            playerType = playerType,
            modeValue = mode,
            upstreamUrl = upstream.url,
            upstreamHeaders = getNetworkHeaders(file),
            contentLength = upstream.contentLength,
            prePlayRangeMinIntervalMs = interval,
            fileName = fileName,
            autoEnabled = true,
            onRangeUnsupported = buildRangeUnsupportedRefreshSupplier(file),
        )
    }

    private fun buildRangeUnsupportedRefreshSupplier(file: StorageFile): () -> HttpPlayServer.UpstreamSource? =
        {
            synchronized(rangeUnsupportedRefreshLock) {
                runCatching {
                    runBlocking {
                        val upstream = resolveUpstream(file, forceRefresh = true)
                        HttpPlayServer.UpstreamSource(
                            url = upstream.url,
                            contentLength = upstream.contentLength,
                        )
                    }
                }.getOrNull()
            }
        }

    override fun supportedPlayerTypes(): Set<PlayerType> =
        setOf(
            PlayerType.TYPE_EXO_PLAYER,
            PlayerType.TYPE_VLC_PLAYER,
            PlayerType.TYPE_MPV_PLAYER,
        )

    override fun preferredPlayerType(): PlayerType = PlayerType.TYPE_EXO_PLAYER

    override fun getNetworkHeaders(): Map<String, String>? = BAIDU_PAN_HEADERS

    override fun getNetworkHeaders(file: StorageFile): Map<String, String>? = BAIDU_PAN_HEADERS

    override suspend fun test(): Boolean = runCatching { repository.xpanUinfo().getOrThrow() }.isSuccess

    override fun supportSearch(): Boolean = true

    override suspend fun search(keyword: String): List<StorageFile> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            return openDirectory(directory ?: getRootFile() ?: return emptyList(), refresh = false)
        }
        if (trimmed.length > MAX_SEARCH_KEYWORD_LENGTH) {
            throw IllegalArgumentException("关键词过长（最多 $MAX_SEARCH_KEYWORD_LENGTH 字符）")
        }

        val dirPath = directory?.filePath()?.takeIf { it.isNotBlank() }
        val items =
            repository
                .search(
                    dir = dirPath,
                    keyword = trimmed,
                    recursion = true,
                    category = SEARCH_CATEGORY_VIDEO,
                ).getOrThrow()

        return items.map { BaiduPanStorageFile(it, this) }
    }

    override fun hasMore(): Boolean = pagingHasMore

    override suspend fun reset() {
        val dirPath = directory?.filePath()?.takeIf { it.isNotBlank() }
        resetPaging(dirPath?.let(::normalizePath))
    }

    override suspend fun loadMore(): Result<List<StorageFile>> {
        val dirPath = directory?.filePath()?.takeIf { it.isNotBlank() }?.let(::normalizePath)
            ?: "/"

        if (pagingDir != dirPath) {
            resetPaging(dirPath)
        }
        if (!pagingHasMore) {
            state = PagedStorage.State.NO_MORE
            return Result.success(emptyList())
        }

        state = PagedStorage.State.LOADING
        return runCatching {
            val response =
                repository.xpanList(
                    dir = dirPath,
                    start = pagingStart,
                    limit = DEFAULT_PAGE_LIMIT,
                ).getOrThrow()

            val items = response.list.orEmpty()
            pagingStart += items.size
            pagingHasMore = items.size >= DEFAULT_PAGE_LIMIT
            items.map { BaiduPanStorageFile(it, this) }
        }.onSuccess {
            state = if (pagingHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }.onFailure {
            state = PagedStorage.State.ERROR
        }
    }

    override fun close() {
        // do nothing
    }

    private data class Upstream(
        val url: String,
        val contentLength: Long
    )

    private suspend fun resolveUpstream(
        file: StorageFile,
        forceRefresh: Boolean
    ): Upstream {
        val payload = file.payloadAs<BaiduPanXpanFileItem>() ?: throw IllegalStateException("无法获取文件信息")
        if (payload.fsId <= 0L) {
            throw IllegalStateException("无效文件ID")
        }

        val cachedLength = runCatching { file.fileLength() }.getOrNull() ?: -1L

        val entry =
            dlinkCache.resolve(
                fsId = payload.fsId,
                forceRefresh = forceRefresh,
            ) {
                val response = repository.xpanFileMetas(fsIds = listOf(payload.fsId)).getOrThrow()
                val meta = response.list.orEmpty().firstOrNull { it.fsId == payload.fsId }
                    ?: throw IllegalStateException("获取播放链接失败")

                val dlink = meta.dlink?.trim().orEmpty()
                if (dlink.isBlank()) {
                    throw IllegalStateException("获取播放链接失败")
                }

                val nowMs = System.currentTimeMillis()
                BaiduPanDlinkCache.Entry(
                    fsId = payload.fsId,
                    dlink = dlink,
                    expiresAtMs = nowMs + TimeUnit.HOURS.toMillis(8),
                    contentLength = meta.size ?: cachedLength,
                )
            }

        val accessToken = tokenManager.requireAccessToken(forceRefresh = false)
        val url = withAccessToken(entry.dlink, accessToken)
        return Upstream(
            url = url,
            contentLength = (entry.contentLength.takeIf { it > 0 } ?: cachedLength).coerceAtLeast(-1L),
        )
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun resetPaging(dirPath: String?) {
        pagingDir = dirPath
        pagingStart = 0
        pagingHasMore = true
        state = PagedStorage.State.IDLE
    }

    private fun withAccessToken(
        url: String,
        accessToken: String
    ): String {
        val raw = url.trim()
        if (raw.isBlank()) return raw

        val replaced =
            raw.replace(
                Regex("([?&])access_token=[^&]*"),
                "$1access_token=$accessToken",
            )
        if (replaced != raw) {
            return replaced
        }

        val separator = if (raw.contains("?")) "&" else "?"
        return raw + separator + "access_token=" + accessToken
    }

    companion object {
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val DEFAULT_PAGE_LIMIT = 200
        private const val MAX_SEARCH_KEYWORD_LENGTH = 30
        private const val SEARCH_CATEGORY_VIDEO = 1
        private val BAIDU_PAN_HEADERS = mapOf(HEADER_USER_AGENT to "pan.baidu.com")
    }
}
