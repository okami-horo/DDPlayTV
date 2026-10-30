package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.repository.Cloud115Repository
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.cloud115.auth.Cloud115AuthStore
import com.xyoye.common_component.storage.cloud115.auth.Cloud115NotConfiguredException
import com.xyoye.common_component.storage.cloud115.net.Cloud115Headers
import com.xyoye.common_component.storage.cloud115.path.Cloud115FolderInfoCache
import com.xyoye.common_component.storage.cloud115.play.Cloud115DownUrlCache
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.common_component.storage.file.impl.Cloud115StorageFile
import com.xyoye.common_component.storage.file.payloadAs
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportProvider
import com.xyoye.common_component.storage.transport.directPlaybackTransport
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.data.cloud115.Cloud115FileInfo
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.InputStream

class Cloud115Storage internal constructor(
    library: MediaLibraryEntity,
    private val pageLoader: (suspend (String, Int) -> List<Cloud115FileInfo>)?
) : AbstractStorage(library),
    PagedStorage,
    AuthStorage,
    PlaybackTransportProvider {
    constructor(library: MediaLibraryEntity) : this(library, null)

    private val rangeUnsupportedRefreshLock = Any()

    private val storageKey = Cloud115AuthStore.storageKey(library)
    private val repository = Cloud115Repository(storageKey)
    private val downUrlCache = Cloud115DownUrlCache()
    private val folderInfoCache = Cloud115FolderInfoCache(repository)

    private var pagingCid: String? = null
    private var pagingOffset: Int = 0
    private var pagingHasMore: Boolean = true

    override var state: PagedStorage.State = PagedStorage.State.IDLE

    override fun snapshotPaging() = PagedStorage.Snapshot(pagingCid, pagingOffset, pagingHasMore, state)

    override fun restorePaging(snapshot: PagedStorage.Snapshot) {
        pagingCid = snapshot.directoryKey
        pagingOffset = snapshot.offset
        pagingHasMore = snapshot.hasMore
        state = snapshot.state
    }

    override fun isConnected(): Boolean = repository.isAuthorized()

    override fun requiresLogin(directory: StorageFile?): Boolean = !isConnected()

    override fun loginActionText(directory: StorageFile?): String = "授权"

    override suspend fun getRootFile(): StorageFile? {
        if (!repository.isAuthorized()) {
            LogFacade.i(
                LogModule.STORAGE,
                LOG_TAG,
                "getRootFile requires authorization",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                ),
            )
            throw Cloud115NotConfiguredException("请先完成授权")
        }

        repository.cookieStatus(forceCheck = false).getOrThrow()
        return Cloud115StorageFile.root(this)
    }

    override suspend fun openFile(file: StorageFile): InputStream? {
        if (file.isDirectory()) {
            return null
        }

        return runCatching {
            val upstream = resolveUpstream(file, forceRefresh = false)
            ResourceRepository
                .getResourceResponseBody(
                    url = upstream.url,
                    headers = getNetworkHeaders(file).orEmpty(),
                ).getOrNull()
                ?.byteStream()
        }.getOrElse { t ->
            val fid = file.payloadAs<Cloud115FileInfo>()?.fid.orEmpty()
            val filePath = runCatching { file.filePath() }.getOrNull()
            val cookie = redactedCookie()
            LogFacade.e(
                LogModule.STORAGE,
                LOG_TAG,
                "open file failed",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                    "filePath" to filePath.orEmpty(),
                    "fid" to fid,
                    "cookie" to cookie,
                    "exception" to t::class.java.simpleName,
                ),
                t,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "Cloud115Storage",
                "openFile",
                "storageId=${library.id} storageKey=$storageKey filePath=$filePath fid=$fid cookie=$cookie",
            )
            throw t
        }
    }

    override suspend fun openDirectory(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        val cid = resolveDirectoryCid(file)

        val items =
            runCatching {
                loadPage(cid, 0)
            }.getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                val dirPath = runCatching { file.filePath() }.getOrNull()
                val cookie = redactedCookie()
                LogFacade.e(
                    LogModule.STORAGE,
                    LOG_TAG,
                    "open directory failed",
                    mapOf(
                        "storageId" to library.id.toString(),
                        "storageKey" to storageKey,
                        "dirPath" to dirPath.orEmpty(),
                        "cid" to cid,
                        "refresh" to refresh.toString(),
                        "cookie" to cookie,
                        "exception" to t::class.java.simpleName,
                    ),
                    t,
                )
                ErrorReportHelper.postCatchedExceptionWithContext(
                    t,
                    "Cloud115Storage",
                    "openDirectory",
                    "storageId=${library.id} storageKey=$storageKey dirPath=$dirPath cid=$cid refresh=$refresh cookie=$cookie",
                )
                throw t
            }

        val parentPath = file.filePath()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        directory = file
        pagingCid = cid
        val files = items.map { Cloud115StorageFile(it, parentPath, this) }

        pagingOffset = files.size
        pagingHasMore = files.size >= DEFAULT_PAGE_LIMIT
        state = if (pagingHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE

        directoryFiles = files
        return directoryFiles
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> {
        val cid = resolveDirectoryCid(file)
        val response =
            repository
                .listFiles(
                    cid = cid,
                    limit = DEFAULT_PAGE_LIMIT,
                    offset = 0,
                    showDir = 1,
                ).getOrThrow()
        val parentPath = file.filePath()
        return response.data.orEmpty().map { Cloud115StorageFile(it, parentPath, this) }
    }

    override fun hasMore(): Boolean = pagingHasMore

    override suspend fun reset() {
        val cid = directory?.let { resolveDirectoryCid(it) } ?: ROOT_CID
        resetPaging(cid)
    }

    override suspend fun loadMore(): Result<List<StorageFile>> {
        val currentDirectory = directory ?: return Result.success(emptyList())
        val cid = resolveDirectoryCid(currentDirectory)
        if (pagingCid != cid) {
            resetPaging(cid)
        }
        if (!pagingHasMore) {
            state = PagedStorage.State.NO_MORE
            return Result.success(emptyList())
        }

        state = PagedStorage.State.LOADING
        val dirPath = runCatching { currentDirectory.filePath() }.getOrNull()
        return runCatching {
            val parentPath = currentDirectory.filePath()
            val items = loadPage(cid, pagingOffset)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val files = items.map { Cloud115StorageFile(it, parentPath, this) }
            pagingOffset += files.size
            pagingHasMore = files.size >= DEFAULT_PAGE_LIMIT
            files
        }.onSuccess {
            state = if (pagingHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            state = PagedStorage.State.ERROR
            val cookie = redactedCookie()
            LogFacade.e(
                LogModule.STORAGE,
                LOG_TAG,
                "load more failed",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                    "dirPath" to dirPath.orEmpty(),
                    "cid" to cid,
                    "offset" to pagingOffset.toString(),
                    "cookie" to cookie,
                    "exception" to it::class.java.simpleName,
                ),
                it,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                it,
                "Cloud115Storage",
                "loadMore",
                "storageId=${library.id} storageKey=$storageKey dirPath=$dirPath cid=$cid offset=$pagingOffset cookie=$cookie",
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

        if (normalized == "/" && isDirectory) {
            return getRootFile()
        }

        val segments =
            Uri
                .parse(normalized)
                .pathSegments
                .filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        var currentCid = ROOT_CID
        var currentPath = "/"

        for ((index, id) in segments.withIndex()) {
            val expectingDirectory = index != segments.lastIndex || isDirectory
            val item = findInDirectory(cid = currentCid, id = id, expectingDirectory = expectingDirectory) ?: return null

            val storageFile = Cloud115StorageFile(fileInfo = item, parentPath = currentPath, storage = this)
            if (index == segments.lastIndex) {
                return storageFile
            }

            currentCid = item.cid?.trim()?.takeIf { it.isNotBlank() } ?: return null
            currentPath = storageFile.filePath()
        }

        return null
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? =
        history.storagePath
            ?.let { pathFile(it, isDirectory = false) }
            ?.also { it.playHistory = history }

    override suspend fun acquirePlaybackTransport(
        file: StorageFile,
        profile: PlaybackProfile
    ): PlaybackTransportLease {
        val playerType = profile.playerType
        return runCatching {
            check(file.isVideoFile()) { "该文件不是视频，无法播放" }

            val upstream = resolveUpstream(file, forceRefresh = false)

            val fileName = runCatching { file.fileName() }.getOrNull().orEmpty().ifBlank { "video" }

            val (mode, interval) =
                when (playerType) {
                    PlayerType.TYPE_MPV_PLAYER ->
                        PlayerConfig.getMpvLocalProxyMode() to PlayerConfig.getMpvProxyRangeMinIntervalMs().toLong()
                    PlayerType.TYPE_EXO_PLAYER ->
                        PlayerConfig.getExoLocalProxyMode() to PlayerConfig.getExoProxyRangeMinIntervalMs().toLong()
                    else -> return@runCatching directPlaybackTransport(upstream.url)
                }

            LogFacade.d(
                LogModule.STORAGE,
                LOG_TAG,
                "acquirePlaybackTransport local proxy policy",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                    "playerType" to playerType.name,
                    "mode" to mode.toString(),
                    "intervalMs" to interval.toString(),
                    "contentLength" to upstream.contentLength.toString(),
                ),
            )

            LocalProxy.acquireIfNeeded(
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
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            val fid = file.payloadAs<Cloud115FileInfo>()?.fid.orEmpty()
            val filePath = runCatching { file.filePath() }.getOrNull()
            val cookie = redactedCookie()
            LogFacade.e(
                LogModule.STORAGE,
                LOG_TAG,
                "create play url failed",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                    "filePath" to filePath.orEmpty(),
                    "fid" to fid,
                    "playerType" to playerType.name,
                    "cookie" to cookie,
                    "exception" to t::class.java.simpleName,
                ),
                t,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "Cloud115Storage",
                "acquirePlaybackTransport",
                "storageId=${library.id} storageKey=$storageKey filePath=$filePath fid=$fid playerType=${playerType.name} cookie=$cookie",
            )
            throw t
        }
    }

    private fun buildRangeUnsupportedRefreshSupplier(file: StorageFile): () -> LocalProxy.UpstreamSource? =
        {
            synchronized(rangeUnsupportedRefreshLock) {
                runCatching {
                    val fid = file.payloadAs<Cloud115FileInfo>()?.fid.orEmpty()
                    LogFacade.w(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "range unsupported, refresh upstream",
                        mapOf(
                            "storageId" to library.id.toString(),
                            "storageKey" to storageKey,
                            "filePath" to runCatching { file.filePath() }.getOrNull().orEmpty(),
                            "fid" to fid,
                        ),
                    )
                    runBlocking {
                        val upstream = resolveUpstream(file, forceRefresh = true)
                        LocalProxy.UpstreamSource(
                            url = upstream.url,
                            headers = getNetworkHeaders(file).orEmpty(),
                            contentLength = upstream.contentLength,
                        )
                    }
                }.onFailure { t ->
                    val fid = file.payloadAs<Cloud115FileInfo>()?.fid.orEmpty()
                    val filePath = runCatching { file.filePath() }.getOrNull()
                    val cookie = redactedCookie()
                    LogFacade.e(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "range unsupported refresh failed",
                        mapOf(
                            "storageId" to library.id.toString(),
                            "storageKey" to storageKey,
                            "filePath" to filePath.orEmpty(),
                            "fid" to fid,
                            "cookie" to cookie,
                            "exception" to t::class.java.simpleName,
                        ),
                        t,
                    )
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "Cloud115Storage",
                        "rangeUnsupportedRefresh",
                        "storageId=${library.id} storageKey=$storageKey filePath=$filePath fid=$fid cookie=$cookie",
                    )
                }.getOrNull()
            }
        }

    override fun getNetworkHeaders(): Map<String, String> {
        val cookie =
            Cloud115AuthStore
                .read(storageKey)
                .cookie
                ?.trim()
                .orEmpty()
        return buildMap {
            put(Cloud115Headers.HEADER_USER_AGENT, Cloud115Headers.appUserAgent())
            if (cookie.isNotBlank()) {
                put(Cloud115Headers.HEADER_COOKIE, cookie)
            }
        }
    }

    override fun getNetworkHeaders(file: StorageFile): Map<String, String>? = getNetworkHeaders()

    override suspend fun test(): Boolean = repository.cookieStatus(forceCheck = true).isSuccess

    override fun supportSearch(): Boolean = true

    override suspend fun search(keyword: String): List<StorageFile> {
        val trimmed = validateSearchKeyword(keyword)
        if (trimmed.isEmpty()) {
            return directoryFiles
        }

        val cid = directory?.let { resolveDirectoryCid(it) } ?: ROOT_CID
        val dirPath = runCatching { directory?.filePath() }.getOrNull()
        val response =
            runCatching {
                repository
                    .searchFiles(
                        searchValue = trimmed,
                        cid = cid,
                        type = SEARCH_TYPE_VIDEO,
                        countFolders = SEARCH_COUNT_FOLDERS_ONLY_FILE,
                        limit = DEFAULT_PAGE_LIMIT,
                        offset = 0,
                    ).getOrThrow()
            }.getOrElse { t ->
                val cookie = redactedCookie()
                LogFacade.e(
                    LogModule.STORAGE,
                    LOG_TAG,
                    "search failed",
                    mapOf(
                        "storageId" to library.id.toString(),
                        "storageKey" to storageKey,
                        "dirPath" to dirPath.orEmpty(),
                        "cid" to cid,
                        "keywordLength" to trimmed.length.toString(),
                        "cookie" to cookie,
                        "exception" to t::class.java.simpleName,
                    ),
                    t,
                )
                ErrorReportHelper.postCatchedExceptionWithContext(
                    t,
                    "Cloud115Storage",
                    "search",
                    "storageId=${library.id} storageKey=$storageKey dirPath=$dirPath cid=$cid keywordLength=${trimmed.length} cookie=$cookie",
                )
                throw t
            }

        val files = mutableListOf<StorageFile>()
        for (item in response.data.orEmpty()) {
            if (!isPlayableVideoSearchItem(item)) {
                continue
            }

            val breadcrumbIds =
                try {
                    folderInfoCache.resolveBreadcrumbIds(item.cid?.trim().orEmpty())
                } catch (_: Exception) {
                    emptyList()
                }
            val parentPath = buildParentPath(breadcrumbIds)
            files.add(Cloud115StorageFile(fileInfo = item, parentPath = parentPath, storage = this))
        }

        return files
    }

    private suspend fun findInDirectory(
        cid: String,
        id: String,
        expectingDirectory: Boolean
    ): Cloud115FileInfo? {
        var offset = 0
        while (true) {
            val response =
                repository
                    .listFiles(
                        cid = cid,
                        limit = PATH_RESOLVE_PAGE_LIMIT,
                        offset = offset,
                        showDir = 1,
                    ).getOrThrow()

            val items = response.data.orEmpty()
            val target =
                items.firstOrNull { item ->
                    val itemFid = item.fid?.trim().orEmpty()
                    val itemCid = item.cid?.trim().orEmpty()
                    val itemIsDirectory = itemFid.isBlank()
                    val itemId = if (itemIsDirectory) itemCid else itemFid
                    itemId == id && itemIsDirectory == expectingDirectory
                }
            if (target != null) return target

            if (items.size < PATH_RESOLVE_PAGE_LIMIT) {
                return null
            }

            offset += items.size
        }
    }

    private data class Upstream(
        val url: String,
        val contentLength: Long
    )

    private suspend fun resolveUpstream(
        file: StorageFile,
        forceRefresh: Boolean
    ): Upstream {
        val payload = file.payloadAs<Cloud115FileInfo>() ?: throw IllegalStateException("无法获取文件信息")
        val fid = requireNonBlankIdentifier(payload.fid, "无效文件ID")
        val pickCode = requireNonBlankIdentifier(payload.pc, "无法获取播放链接（pick_code 为空）")

        val cachedLength = runCatching { file.fileLength() }.getOrNull() ?: -1L

        val entry =
            downUrlCache.resolve(fid = fid, forceRefresh = forceRefresh) {
                val nowMs = System.currentTimeMillis()
                val response = repository.downloadUrl(pickCode = pickCode).getOrThrow()
                val url = requireNonBlankIdentifier(response.url, "获取播放链接失败")

                Cloud115DownUrlCache.Entry(
                    fid = fid,
                    pickCode = pickCode,
                    url = url,
                    userAgent = response.userAgent,
                    fileSize = payload.s ?: cachedLength,
                    updatedAtMs = nowMs,
                )
            }

        return Upstream(
            url = entry.url,
            contentLength = (entry.fileSize.takeIf { it > 0 } ?: cachedLength).coerceAtLeast(-1L),
        )
    }

    companion object {
        private const val LOG_TAG = "cloud115_storage"

        const val ROOT_CID: String = "0"
        private const val DEFAULT_PAGE_LIMIT: Int = 200
        private const val PATH_RESOLVE_PAGE_LIMIT: Int = 200
        private const val MAX_SEARCH_KEYWORD_LENGTH: Int = 30
        private const val SEARCH_TYPE_VIDEO: Int = 4
        private const val SEARCH_COUNT_FOLDERS_ONLY_FILE: Int = 0

        internal fun validateSearchKeyword(keyword: String): String {
            val trimmed = keyword.trim()
            require(trimmed.length <= MAX_SEARCH_KEYWORD_LENGTH) {
                "关键词过长（最多 $MAX_SEARCH_KEYWORD_LENGTH 字符）"
            }
            return trimmed
        }

        internal fun requireNonBlankIdentifier(
            rawValue: String?,
            message: String,
        ): String {
            val value = rawValue?.trim().orEmpty()
            check(value.isNotBlank()) { message }
            return value
        }
    }

    private fun resolveDirectoryCid(file: StorageFile): String {
        val item =
            file
                .payloadAs<Cloud115FileInfo>()
                ?: throw IllegalStateException("Missing Cloud115 payload")

        return item.cid?.trim()?.takeIf { it.isNotBlank() } ?: ROOT_CID
    }

    private fun resetPaging(cid: String) {
        pagingCid = cid
        pagingOffset = 0
        pagingHasMore = true
        state = PagedStorage.State.IDLE
    }

    private suspend fun loadPage(
        cid: String,
        offset: Int
    ): List<Cloud115FileInfo> =
        pageLoader?.invoke(cid, offset)
            ?: repository
                .listFiles(cid, DEFAULT_PAGE_LIMIT, offset, showDir = 1)
                .getOrThrow()
                .data
                .orEmpty()

    private fun redactedCookie(): String =
        runCatching { Cloud115Headers.redactCookie(Cloud115AuthStore.read(storageKey).cookie) }
            .getOrDefault("")

    private fun buildParentPath(breadcrumbIds: List<String>): String {
        if (breadcrumbIds.isEmpty()) {
            return "/"
        }
        return "/" + breadcrumbIds.joinToString("/") + "/"
    }

    private fun isPlayableVideoSearchItem(item: Cloud115FileInfo): Boolean {
        val fileId = item.fid?.trim().orEmpty()
        if (fileId.isBlank()) return false

        val pickCode = item.pc?.trim().orEmpty()
        if (pickCode.isBlank()) return false

        val name = item.n?.trim().orEmpty()
        if (name.isBlank()) return false

        return com.xyoye.common_component.utils
            .isVideoFile(name)
    }
}
