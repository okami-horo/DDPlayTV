package com.xyoye.common_component.storage.impl

import com.xyoye.common_component.network.repository.Open115Repository
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.Open115StorageFile
import com.xyoye.common_component.storage.file.payloadAs
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.storage.open115.auth.Open115NotConfiguredException
import com.xyoye.common_component.storage.open115.net.Open115Headers
import com.xyoye.data_component.data.open115.Open115FileItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import java.io.InputStream

class Open115Storage(
    library: MediaLibraryEntity
) : AbstractStorage(library),
    AuthStorage {
    private val storageKey = Open115AuthStore.storageKey(library)
    private val repository = Open115Repository(storageKey)

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

    override suspend fun openFile(file: StorageFile): InputStream? = null

    override suspend fun openDirectory(
        file: StorageFile,
        refresh: Boolean
    ): List<StorageFile> {
        directory = file
        directoryFiles = listFiles(file)
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

    override suspend fun createPlayUrl(file: StorageFile): String? = null

    override fun getNetworkHeaders(): Map<String, String> =
        mapOf(Open115Headers.HEADER_USER_AGENT to Open115Headers.USER_AGENT)

    override fun getNetworkHeaders(file: StorageFile): Map<String, String>? = getNetworkHeaders()

    override suspend fun test(): Boolean = repository.userInfo().isSuccess

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

    companion object {
        const val ROOT_CID: String = "0"
        private const val DEFAULT_PAGE_LIMIT: Int = 200
        private const val PATH_RESOLVE_PAGE_LIMIT: Int = 1150
    }
}
