package com.xyoye.common_component.storage.impl

import java.io.File
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException

/**
 * 轻量封装 WebDAV 上传能力，依赖 OkHttp。
 */
class WebDavClient(
    private val httpClient: OkHttpClient
) {
    private var endpoint: String = ""
    private var username: String? = null
    private var password: String? = null

    fun updateEndpoint(url: String) {
        endpoint = url.trimEnd('/')
    }

    fun updateAccount(user: String, pass: String) {
        username = user
        password = pass
    }

    fun ensureDirectory(path: String) {
        if (path.isEmpty()) {
            return
        }
        val url = "$endpoint/${path.trimStart('/')}"
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", EmptyRequestBody)
            .apply { applyAuth(this) }
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && !response.isMethodNotAllowed()) {
                    throw IOException("MKCOL failed: ${response.code}")
                }
            }
        } catch (_: Exception) {
        }
    }

    fun uploadFile(path: String, file: File) {
        val url = "$endpoint/${path.trimStart('/')}"
        val request = Request.Builder()
            .url(url)
            .put(file.asRequestBody("application/octet-stream".toMediaType()))
            .apply { applyAuth(this) }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload failed: ${response.code}")
            }
        }
    }

    private fun applyAuth(builder: Request.Builder) {
        val user = username ?: return
        val pass = password ?: ""
        val credential = Credentials.basic(user, pass)
        builder.addHeader("Authorization", credential)
    }

    private fun Response.isMethodNotAllowed(): Boolean {
        return code == 405
    }

    private object EmptyRequestBody : okhttp3.RequestBody() {
        override fun contentType() = null
        override fun writeTo(sink: okio.BufferedSink) {}
    }
}
