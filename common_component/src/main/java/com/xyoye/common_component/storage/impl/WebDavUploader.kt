package com.xyoye.common_component.storage.impl

import android.util.Log
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.sardine.Sardine
import com.xyoye.sardine.impl.OkHttpSardine
import okhttp3.OkHttpClient
import java.io.File

/**
 * 负责 WebDAV 上传操作，基于 OkHttpSardine。
 */
class WebDavUploader(
    okHttpClient: OkHttpClient,
    baseUrl: String,
    username: String?,
    password: String?
) {
    private val sardine: Sardine = OkHttpSardine(okHttpClient).apply {
        val user = username.orEmpty()
        val pass = password.orEmpty()
        if (user.isNotEmpty()) {
            setCredentials(user, pass)
        }
    }

    private val endpoint: String = baseUrl.trimEnd('/')

    fun ensureDirectory(path: String) {
        if (path.isEmpty()) return
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var currentPath = StringBuilder()
        for (segment in segments) {
            if (currentPath.isNotEmpty()) {
                currentPath.append('/')
            }
            currentPath.append(segment)
            val target = buildUrl(currentPath.toString())
            try {
                sardine.createDirectory(target)
            } catch (e: Exception) {
                if (isDirectoryExistsError(e).not()) {
                    Log.w("WebDavUploader", "MKCOL $target 失败: ${e.message}")
                }
            }
        }
    }

    fun uploadFile(remotePath: String, file: File) {
        val targetUrl = buildUrl(remotePath)
        try {
            sardine.put(targetUrl, file, "application/octet-stream")
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "WebDavUploader",
                "uploadFile",
                "上传失败: $targetUrl"
            )
            throw e
        }
    }

    private fun isDirectoryExistsError(e: Exception): Boolean {
        val message = e.message ?: return false
        return message.contains("405") || message.contains("409") || message.contains("207")
    }

    private fun buildUrl(path: String): String {
        val cleaned = path.trim('/')
        return if (cleaned.isEmpty()) endpoint else "$endpoint/$cleaned"
    }
}
