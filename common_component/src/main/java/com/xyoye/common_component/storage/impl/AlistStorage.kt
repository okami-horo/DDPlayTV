package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.network.repository.AlistRepository
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.storage.file.impl.AlistStorageFile
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.alist.AlistFileData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.PlayerType
import java.io.InputStream

/**
 * Created by xyoye on 2024/1/20.
 */

class AlistStorage(
    library: MediaLibraryEntity
) : AbstractStorage(library) {
    private var token: String = ""

    private val rootUrl by lazy { rootUri.toString() }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> =
        AlistRepository
            .openDirectory(rootUrl, token, file.filePath())
            .getOrNull()
            ?.successData
            ?.fileList
            ?.map {
                AlistStorageFile(file.filePath(), it, this)
            } ?: emptyList()

    override suspend fun getRootFile(): StorageFile? {
        val newToken = refreshToken() ?: return null
        this.token = newToken

        val result = AlistRepository.getUserInfo(rootUrl, token)
        if (result.isFailure) {
            ToastCenter.showToast("${result.exceptionOrNull()?.message}")
            return null
        }

        return result
            .getOrNull()
            ?.successData
            ?.let {
                AlistFileData("/", true)
            }?.let {
                AlistStorageFile("", it, this)
            }
    }

    override suspend fun openFile(file: StorageFile): InputStream? {
        val rawUrl =
            getStorageFileUrl(file)
                ?: return null

        return ResourceRepository
            .getResourceResponseBody(rawUrl)
            .getOrNull()
            ?.byteStream()
    }

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? {
        if (token.isEmpty()) {
            token = refreshToken() ?: return null
        }

        val pathUri = Uri.parse(path)
        val fileName = pathUri.lastPathSegment
        val parentPath = pathUri.path?.removeSuffix("/$fileName") ?: "/"
        return AlistRepository
            .openFile(rootUrl, token, path)
            .getOrNull()
            ?.successData
            ?.let {
                AlistStorageFile(parentPath, it, this)
            }
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? =
        history.storagePath
            ?.let { pathFile(it, false) }
            ?.also { it.playHistory = history }

    override suspend fun createPlayUrl(file: StorageFile): String? {
        return if (PlayerConfig.getUsePlayerType() == PlayerType.TYPE_MPV_PLAYER.value) {
            val upstream = getStorageFileProxyUrl(file) ?: getStorageFileUrl(file) ?: return null
            val fileName = runCatching { file.fileName() }.getOrNull().orEmpty().ifEmpty { "video" }
            val playServer = HttpPlayServer.getInstance()
            val started = playServer.startSync()
            if (started.not()) {
                return null
            }
            playServer.generatePlayUrl(
                upstreamUrl = upstream,
                // Provide file length so the proxy can expose a seekable HTTP stream to mpv (Range support).
                contentLength = runCatching { file.fileLength() }.getOrNull() ?: -1L,
                fileName = fileName,
            )
        } else {
            getStorageFileUrl(file)
        }
    }

    override suspend fun test(): Boolean = refreshToken()?.isNotEmpty() == true

    private suspend fun refreshToken(): String? {
        val username = library.account ?: return null
        val password = library.password ?: return null

        return AlistRepository
            .login(rootUrl, username, password)
            .getOrNull()
            ?.successData
            ?.token
    }

    private suspend fun getStorageFileUrl(file: StorageFile): String? {
        val cachedRawUrl = file.getFile<AlistFileData>()?.rawUrl?.takeIf { it.isNotEmpty() }

        // 优先尝试获取最新直链；若失败再强制刷新 token 重试，最后回退到旧直链
        fetchRawUrl(file, forceRefreshToken = false)?.let { return it }
        fetchRawUrl(file, forceRefreshToken = true)?.let { return it }

        return cachedRawUrl
    }

    private suspend fun getStorageFileProxyUrl(file: StorageFile): String? {
        val cachedSign = file.getFile<AlistFileData>()?.sign?.takeIf { it.isNotEmpty() }

        fetchSign(file, forceRefreshToken = false)
            ?.let { return buildProxyUrl(file.filePath(), it) }
        fetchSign(file, forceRefreshToken = true)
            ?.let { return buildProxyUrl(file.filePath(), it) }

        return cachedSign?.let { buildProxyUrl(file.filePath(), it) }
    }

    private fun buildProxyUrl(
        path: String,
        sign: String
    ): String {
        val baseUrl = rootUrl.removeSuffix("/")
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val encodedPath = Uri.encode(normalizedPath, "/")
        val encodedSign = Uri.encode(sign)
        return "$baseUrl/d$encodedPath?sign=$encodedSign"
    }

    private suspend fun fetchRawUrl(
        file: StorageFile,
        forceRefreshToken: Boolean
    ): String? {
        if (!ensureToken(forceRefreshToken)) {
            return null
        }
        return AlistRepository
            .openFile(rootUrl, token, file.filePath())
            .getOrNull()
            ?.successData
            ?.rawUrl
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchSign(
        file: StorageFile,
        forceRefreshToken: Boolean
    ): String? {
        if (!ensureToken(forceRefreshToken)) {
            return null
        }
        return AlistRepository
            .openFile(rootUrl, token, file.filePath())
            .getOrNull()
            ?.successData
            ?.sign
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun ensureToken(forceRefresh: Boolean): Boolean {
        if (token.isNotEmpty() && !forceRefresh) {
            return true
        }
        val refreshed = refreshToken()
        if (refreshed.isNullOrEmpty()) {
            return token.isNotEmpty()
        }
        token = refreshed
        return true
    }
}
