package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.network.config.HeaderKey
import com.xyoye.common_component.network.helper.UnsafeOkHttpClient
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.MpvLocalProxy
import com.xyoye.common_component.storage.file.impl.WebDavStorageFile
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.sardine.DavResource
import com.xyoye.sardine.impl.OkHttpSardine
import com.xyoye.sardine.util.SardineConfig
import okhttp3.Credentials
import java.io.InputStream
import java.net.URI
import java.util.Date

/**
 * Created by xyoye on 2022/12/29
 */

class WebDavStorage(
    library: MediaLibraryEntity
) : AbstractStorage(library) {
    private val sardine = OkHttpSardine(UnsafeOkHttpClient.client)

    init {
        SardineConfig.isXmlStrictMode = this.library.webDavStrict
        getAccountInfo()?.let {
            sardine.setCredentials(it.first, it.second)
        }
    }

    override suspend fun getRootFile(): StorageFile {
        val rootPath = Uri.parse(library.url).path ?: "/"
        return pathFile(rootPath, true)
    }

    override suspend fun openFile(file: StorageFile): InputStream? =
        try {
            sardine.get(file.fileUrl())
        } catch (e: Exception) {
            e.printStackTrace()
            ErrorReportHelper.postCatchedException(e, "WebDAV", "打开文件失败: ${file.fileUrl()}")
            null
        }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> =
        try {
            sardine
                .list(file.fileUrl())
                .filter { isChildFile(file.fileUrl(), it.href) }
                .map { WebDavStorageFile(it, this) }
        } catch (e: Exception) {
            e.printStackTrace()
            ErrorReportHelper.postCatchedException(e, "WebDAV", "获取文件列表失败: ${file.fileUrl()}")
            emptyList()
        }

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile {
        val hrefUrl = resolvePath(path).toString()
        val davResource = CustomDavResource(hrefUrl)
        return WebDavStorageFile(davResource, this)
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? {
        val storagePath = history.storagePath ?: return null
        return pathFile(storagePath, false).also {
            it.playHistory = history
        }
    }

    override suspend fun createPlayUrl(file: StorageFile): String {
        val upstream = file.fileUrl()
        val contentLength = runCatching { file.fileLength() }.getOrNull() ?: -1L
        val fileName = runCatching { file.fileName() }.getOrNull().orEmpty().ifEmpty { "video" }
        return MpvLocalProxy.wrapIfNeeded(
            upstreamUrl = upstream,
            upstreamHeaders = getNetworkHeaders(),
            contentLength = contentLength,
            fileName = fileName,
            autoEnabled = false,
        )
    }

    override fun getNetworkHeaders(): Map<String, String>? {
        val accountInfo =
            getAccountInfo()
                ?: return null
        val credential = Credentials.basic(accountInfo.first, accountInfo.second)
        return mapOf(Pair(HeaderKey.AUTHORIZATION, credential))
    }

    override suspend fun test(): Boolean =
        try {
            sardine.list(getRootFile().fileUrl())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            ErrorReportHelper.postCatchedException(e, "WebDAV", "连接测试失败: ${library.url}")
            ToastCenter.showError("连接失败: ${e.message}")
            false
        }

    private fun getAccountInfo(): Pair<String, String>? {
        if (library.account.isNullOrEmpty()) {
            return null
        }
        return Pair(library.account ?: "", library.password ?: "")
    }

    private fun isChildFile(
        parent: String,
        child: URI
    ): Boolean {
        try {
            val parentPath = URI(parent).path
            val childPath = child.path
            return parentPath != childPath
        } catch (e: Exception) {
            e.printStackTrace()
            ErrorReportHelper.postCatchedException(e, "WebDAV", "路径解析失败: parent=$parent, child=$child")
        }
        return false
    }

    private class CustomDavResource(
        href: String,
        isDirectory: Boolean = true
    ) : DavResource(
            href,
            Date(),
            Date(),
            if (isDirectory) "httpd/unix-directory" else "application/octet-stream",
            0,
            "",
            "",
            emptyList(),
            "",
            emptyList(),
            emptyMap(),
        )
}
