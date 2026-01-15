package com.xyoye.common_component.storage.impl

import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.network.repository.Open115Repository
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.common_component.storage.file.impl.Open115StorageFile
import com.xyoye.common_component.storage.file.payloadAs
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.storage.open115.auth.Open115NotConfiguredException
import com.xyoye.common_component.storage.open115.net.Open115Headers
import com.xyoye.common_component.storage.open115.path.Open115FolderInfoCache
import com.xyoye.common_component.storage.open115.play.Open115DownUrlCache
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.data.open115.Open115FileItem
import com.xyoye.data_component.data.open115.Open115SearchItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.runBlocking
import java.io.InputStream

class Open115Storage(
    library: MediaLibraryEntity
) : AbstractStorage(library),
    PagedStorage,
    AuthStorage {
    private val rangeUnsupportedRefreshLock = Any()

    private val storageKey = Open115AuthStore.storageKey(library)
    private val repository = Open115Repository(storageKey)
    private val downUrlCache = Open115DownUrlCache()
    private val folderInfoCache = Open115FolderInfoCache(repository)

    private var pagingCid: String? = null
    private var pagingOffset: Int = 0
    private var pagingHasMore: Boolean = true

    override var state: PagedStorage.State = PagedStorage.State.IDLE

    override fun isConnected(): Boolean = repository.isAuthorized()

    override fun requiresLogin(directory: StorageFile?): Boolean = !isConnected()

    override fun loginActionText(directory: StorageFile?): String = "填写 token"

    override suspend fun getRootFile(): StorageFile? {
        if (!repository.isAuthorized()) {
            throw Open115NotConfiguredException("请先配置 115 Open token")
        }

        repository.userInfo().getOrThrow()
        return Open115StorageFile.root(this)
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
            val fid = file.payloadAs<Open115FileItem>()?.fid.orEmpty()
            LogFacade.e(
                LogModule.STORAGE,
                LOG_TAG,
                "open file failed",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                    "filePath" to runCatching { file.filePath() }.getOrNull().orEmpty(),
                    "fid" to fid,
                    "exception" to t::class.java.simpleName,
                ),
                t,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "Open115Storage",
                "openFile",
                "storageId=${library.id} storageKey=$storageKey filePath=${runCatching { file.filePath() }.getOrNull()} fid=$fid",
            )
            throw t
        }
    }

    override suspend fun openDirectory(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        directory = file

        val cid = resolveCid(file)
        if (refresh || pagingCid != cid) {
            resetPaging(cid)
        }

        val response =
            repository.listFiles(
                cid = cid,
                limit = DEFAULT_PAGE_LIMIT,
                offset = 0,
                showDir = "1"
            ).getOrThrow()

        val parentPath = file.filePath()
        val items = response.data.orEmpty()
        cacheFolderParents(items, cid)
        val files = items.map { Open115StorageFile(it, parentPath, this) }

        pagingOffset = files.size
        pagingHasMore = files.size >= DEFAULT_PAGE_LIMIT
        state = if (pagingHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE

        directoryFiles = files
        return directoryFiles
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> {
        val dirItem =
            file
                .payloadAs<Open115FileItem>()
                ?: throw IllegalStateException("Missing Open115 payload")
        val cid = dirItem.fid?.takeIf { it.isNotBlank() } ?: ROOT_CID

        val response =
            repository.listFiles(
                cid = cid,
                limit = DEFAULT_PAGE_LIMIT,
                offset = 0,
                showDir = "1"
            ).getOrThrow()

        val parentPath = file.filePath()
        return response
            .data
            .orEmpty()
            .map { Open115StorageFile(it, parentPath, this) }
    }

    override fun hasMore(): Boolean = pagingHasMore

    override suspend fun reset() {
        val cid = directory?.let { resolveCid(it) } ?: ROOT_CID
        resetPaging(cid)
    }

    override suspend fun loadMore(): Result<List<StorageFile>> {
        val currentDirectory = directory ?: return Result.success(emptyList())
        val cid = resolveCid(currentDirectory)
        if (pagingCid != cid) {
            resetPaging(cid)
        }
        if (!pagingHasMore) {
            state = PagedStorage.State.NO_MORE
            return Result.success(emptyList())
        }

        state = PagedStorage.State.LOADING
        return runCatching {
            val response =
                repository.listFiles(
                    cid = cid,
                    limit = DEFAULT_PAGE_LIMIT,
                    offset = pagingOffset,
                    showDir = "1"
                ).getOrThrow()

            val parentPath = currentDirectory.filePath()
            val items = response.data.orEmpty()
            cacheFolderParents(items, cid)
            val files = items.map { Open115StorageFile(it, parentPath, this) }
            pagingOffset += files.size
            pagingHasMore = files.size >= DEFAULT_PAGE_LIMIT
            files
        }.onSuccess {
            state = if (pagingHasMore) PagedStorage.State.IDLE else PagedStorage.State.NO_MORE
        }.onFailure {
            state = PagedStorage.State.ERROR
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
            android.net.Uri.parse(normalized)
                .pathSegments
                .filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        var currentCid = ROOT_CID
        var currentPath = "/"

        for ((index, fid) in segments.withIndex()) {
            val expectingDirectory = if (index == segments.lastIndex) isDirectory else true
            val item = findInDirectory(cid = currentCid, fid = fid, expectingDirectory = expectingDirectory) ?: return null

            val storageFile = Open115StorageFile(item, currentPath, this)
            if (index == segments.lastIndex) {
                return storageFile
            }

            currentCid = item.fid?.takeIf { it.isNotBlank() } ?: return null
            currentPath = storageFile.filePath()
        }

        return null
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? =
        history.storagePath
            ?.let { pathFile(it, isDirectory = false) }
            ?.also { it.playHistory = history }

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
        val playerType = profile.playerType
        return runCatching {
            if (!file.isVideoFile()) {
                throw IllegalStateException("该文件不是视频，无法播放")
            }

            val upstream = resolveUpstream(file, forceRefresh = false)

            if (playerType != PlayerType.TYPE_MPV_PLAYER && playerType != PlayerType.TYPE_VLC_PLAYER) {
                return@runCatching upstream.url
            }

            val fileName = runCatching { file.fileName() }.getOrNull().orEmpty().ifBlank { "video" }

            val (mode, interval) =
                when (playerType) {
                    PlayerType.TYPE_MPV_PLAYER ->
                        PlayerConfig.getMpvLocalProxyMode() to PlayerConfig.getMpvProxyRangeMinIntervalMs().toLong()
                    PlayerType.TYPE_VLC_PLAYER ->
                        PlayerConfig.getVlcLocalProxyMode() to PlayerConfig.getVlcProxyRangeMinIntervalMs().toLong()
                    else -> return@runCatching upstream.url
                }

            LocalProxy.wrapIfNeeded(
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
            val fid = file.payloadAs<Open115FileItem>()?.fid.orEmpty()
            LogFacade.e(
                LogModule.STORAGE,
                LOG_TAG,
                "create play url failed",
                mapOf(
                    "storageId" to library.id.toString(),
                    "storageKey" to storageKey,
                    "filePath" to runCatching { file.filePath() }.getOrNull().orEmpty(),
                    "fid" to fid,
                    "playerType" to playerType.name,
                    "exception" to t::class.java.simpleName,
                ),
                t,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "Open115Storage",
                "createPlayUrl",
                "storageId=${library.id} storageKey=$storageKey filePath=${runCatching { file.filePath() }.getOrNull()} fid=$fid playerType=${playerType.name}",
            )
            throw t
        }
    }

    private fun buildRangeUnsupportedRefreshSupplier(file: StorageFile): () -> HttpPlayServer.UpstreamSource? =
        {
            synchronized(rangeUnsupportedRefreshLock) {
                runCatching {
                    val fid = file.payloadAs<Open115FileItem>()?.fid.orEmpty()
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
                        HttpPlayServer.UpstreamSource(
                            url = upstream.url,
                            headers = getNetworkHeaders(file).orEmpty(),
                            contentLength = upstream.contentLength,
                        )
                    }
                }.onFailure { t ->
                    val fid = file.payloadAs<Open115FileItem>()?.fid.orEmpty()
                    LogFacade.e(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "range unsupported refresh failed",
                        mapOf(
                            "storageId" to library.id.toString(),
                            "storageKey" to storageKey,
                            "filePath" to runCatching { file.filePath() }.getOrNull().orEmpty(),
                            "fid" to fid,
                            "exception" to t::class.java.simpleName,
                        ),
                        t,
                    )
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "Open115Storage",
                        "rangeUnsupportedRefresh",
                        "storageId=${library.id} storageKey=$storageKey filePath=${runCatching { file.filePath() }.getOrNull()} fid=$fid",
                    )
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

    override fun getNetworkHeaders(): Map<String, String> =
        mapOf(Open115Headers.HEADER_USER_AGENT to Open115Headers.USER_AGENT)

    override fun getNetworkHeaders(file: StorageFile): Map<String, String>? = getNetworkHeaders()

    override suspend fun test(): Boolean = repository.userInfo().isSuccess

    override fun supportSearch(): Boolean = true

    override suspend fun search(keyword: String): List<StorageFile> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            return directoryFiles
        }
        if (trimmed.length > MAX_SEARCH_KEYWORD_LENGTH) {
            throw IllegalArgumentException("关键词过长（最多 $MAX_SEARCH_KEYWORD_LENGTH 字符）")
        }

        val cid = directory?.let { resolveCid(it) } ?: ROOT_CID
        val response =
            repository.searchFiles(
                searchValue = trimmed,
                cid = cid,
                type = SEARCH_TYPE_VIDEO,
                fc = SEARCH_FILE_CATEGORY_ONLY_FILE,
                limit = DEFAULT_PAGE_LIMIT,
                offset = 0,
            ).getOrThrow()

        val files = mutableListOf<StorageFile>()
        for (item in response.data.orEmpty()) {
            if (!isPlayableVideoSearchItem(item)) {
                continue
            }

            val breadcrumbIds =
                try {
                    folderInfoCache.resolveBreadcrumbIds(item.parentId.orEmpty())
                } catch (_: Exception) {
                    emptyList()
                }
            val parentPath = buildParentPath(breadcrumbIds)
            files.add(
                Open115StorageFile(
                    fileItem = item.toFileItem(),
                    parentPath = parentPath,
                    storage = this,
                ),
            )
        }

        return files
    }

    private suspend fun findInDirectory(
        cid: String,
        fid: String,
        expectingDirectory: Boolean
    ): Open115FileItem? {
        var offset = 0
        while (true) {
            val response =
                repository.listFiles(
                    cid = cid,
                    limit = PATH_RESOLVE_PAGE_LIMIT,
                    offset = offset,
                    showDir = "1"
                ).getOrThrow()

            val items = response.data.orEmpty()
            val target =
                items.firstOrNull {
                    it.fid == fid && it.fc == if (expectingDirectory) "0" else "1"
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
        val payload = file.payloadAs<Open115FileItem>() ?: throw IllegalStateException("无法获取文件信息")
        val fid = payload.fid?.trim().orEmpty()
        if (fid.isBlank()) {
            throw IllegalStateException("无效文件ID")
        }
        val pickCode = payload.pc?.trim().orEmpty()
        if (pickCode.isBlank()) {
            throw IllegalStateException("无法获取播放链接（pick_code 为空）")
        }

        val cachedLength = runCatching { file.fileLength() }.getOrNull() ?: -1L
        val nowMs = System.currentTimeMillis()

        if (!forceRefresh) {
            downUrlCache.getValid(fid = fid, nowMs = nowMs)?.let { entry ->
                LogFacade.d(
                    LogModule.STORAGE,
                    LOG_TAG,
                    "downurl cache hit",
                    mapOf(
                        "storageKey" to storageKey,
                        "fid" to fid,
                    ),
                )
                return Upstream(
                    url = entry.url,
                    contentLength = (entry.fileSize.takeIf { it > 0 } ?: cachedLength).coerceAtLeast(-1L),
                )
            }
        }

        val cachedAny = downUrlCache.getAny(fid)
        val entry =
            runCatching {
                if (forceRefresh) {
                    LogFacade.d(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "downurl force refresh",
                        mapOf(
                            "storageKey" to storageKey,
                            "fid" to fid,
                        ),
                    )
                }

                val response = repository.downUrl(pickCode = pickCode).getOrThrow()
                val item =
                    response.data?.get(fid)
                        ?: response.data?.values?.firstOrNull()
                        ?: throw IllegalStateException("获取播放链接失败")

                val url = item.url?.url?.trim().orEmpty()
                if (url.isBlank()) {
                    throw IllegalStateException("获取播放链接失败")
                }

                val length = item.fileSize ?: payload.fs ?: cachedLength

                Open115DownUrlCache.Entry(
                    fid = fid,
                    pickCode = pickCode,
                    url = url,
                    userAgent = Open115Headers.USER_AGENT,
                    fileSize = length,
                    updatedAtMs = nowMs,
                )
            }.onSuccess { fresh ->
                downUrlCache.put(fresh)
                LogFacade.d(
                    LogModule.STORAGE,
                    LOG_TAG,
                    "downurl fetched",
                    mapOf(
                        "storageKey" to storageKey,
                        "fid" to fid,
                        "pickCodeLength" to pickCode.length.toString(),
                        "fileSize" to fresh.fileSize.toString(),
                    ),
                )
            }.getOrElse { t ->
                if (cachedAny != null) {
                    LogFacade.w(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "downurl fetch failed, fallback cache",
                        mapOf(
                            "storageKey" to storageKey,
                            "fid" to fid,
                            "forceRefresh" to forceRefresh.toString(),
                            "exception" to t::class.java.simpleName,
                        ),
                        t,
                    )
                    return Upstream(
                        url = cachedAny.url,
                        contentLength = (cachedAny.fileSize.takeIf { it > 0 } ?: cachedLength).coerceAtLeast(-1L),
                    )
                }

                throw t
            }

        return Upstream(
            url = entry.url,
            contentLength = (entry.fileSize.takeIf { it > 0 } ?: cachedLength).coerceAtLeast(-1L),
        )
    }

    companion object {
        private const val LOG_TAG = "open115_storage"

        const val ROOT_CID: String = "0"
        private const val DEFAULT_PAGE_LIMIT: Int = 200
        private const val PATH_RESOLVE_PAGE_LIMIT: Int = 1150
        private const val MAX_SEARCH_KEYWORD_LENGTH: Int = 30
        private const val SEARCH_TYPE_VIDEO: String = "4"
        private const val SEARCH_FILE_CATEGORY_ONLY_FILE: String = "2"
    }

    private fun resolveCid(file: StorageFile): String {
        val dirItem =
            file
                .payloadAs<Open115FileItem>()
                ?: throw IllegalStateException("Missing Open115 payload")
        return dirItem.fid?.takeIf { it.isNotBlank() } ?: ROOT_CID
    }

    private fun resetPaging(cid: String) {
        pagingCid = cid
        pagingOffset = 0
        pagingHasMore = true
        state = PagedStorage.State.IDLE
    }

    private fun buildParentPath(breadcrumbIds: List<String>): String {
        if (breadcrumbIds.isEmpty()) {
            return "/"
        }
        return "/" + breadcrumbIds.joinToString("/") + "/"
    }

    private fun cacheFolderParents(
        items: List<Open115FileItem>,
        cid: String
    ) {
        items
            .asSequence()
            .filter { it.fc == "0" }
            .mapNotNull { it.fid?.trim()?.takeIf(String::isNotBlank) }
            .forEach { folderInfoCache.cacheParent(folderId = it, parentId = cid) }
    }

    private fun isPlayableVideoSearchItem(item: Open115SearchItem): Boolean {
        val fileId = item.fileId?.trim().orEmpty()
        if (fileId.isBlank()) return false

        val pickCode = item.pickCode?.trim().orEmpty()
        if (pickCode.isBlank()) return false

        return item.fileCategory != "0"
    }

    private fun Open115SearchItem.toFileItem(): Open115FileItem =
        Open115FileItem(
            fid = fileId?.trim(),
            pid = parentId?.trim(),
            fc = "1",
            fn = fileName?.trim(),
            pc = pickCode?.trim(),
            sha1 = null,
            fs = fileSize?.trim()?.toLongOrNull(),
            upt = null,
            uet = null,
            uppt = null,
            isv = 1L,
            ico = ico?.trim(),
            thumb = null,
        )
}
