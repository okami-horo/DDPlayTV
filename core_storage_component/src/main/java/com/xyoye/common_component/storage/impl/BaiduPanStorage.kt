package com.xyoye.common_component.storage.impl

import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.network.repository.BaiduPanRepository
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.storage.AbstractStorage
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
) : AbstractStorage(library) {
    private val rangeUnsupportedRefreshLock = Any()

    private val storageKey = BaiduPanAuthStore.storageKey(library)
    private val repository = BaiduPanRepository(storageKey)
    private val tokenManager = BaiduPanTokenManager(storageKey)
    private val dlinkCache = BaiduPanDlinkCache()

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
        this.directory = file
        this.directoryFiles = listFiles(file)
        return directoryFiles
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> {
        val dir = file.filePath().ifBlank { "/" }
        val response = repository.xpanList(dir = dir).getOrThrow()
        val items = response.list.orEmpty()
        return items.map { BaiduPanStorageFile(it, this) }
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
        val response = repository.xpanList(dir = parentDir).getOrThrow()
        val item =
            response
                .list
                .orEmpty()
                .firstOrNull { it.path == normalized && it.isdir == (if (isDirectory) 1 else 0) }
                ?: return null
        return BaiduPanStorageFile(item, this)
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
        private val BAIDU_PAN_HEADERS = mapOf(HEADER_USER_AGENT to "pan.baidu.com")
    }
}
