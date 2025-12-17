package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * 为 HTTP 请求打印 URL / 响应码 / Content-Type，便于定位播放失败（如直链过期）。
 */
@UnstableApi
class LoggingHttpDataSourceFactory : HttpDataSource.Factory {
    private val delegate = DefaultHttpDataSource.Factory()

    fun setUserAgent(userAgent: String): LoggingHttpDataSourceFactory {
        delegate.setUserAgent(userAgent)
        return this
    }

    fun setAllowCrossProtocolRedirects(allow: Boolean): LoggingHttpDataSourceFactory {
        delegate.setAllowCrossProtocolRedirects(allow)
        return this
    }

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): LoggingHttpDataSourceFactory {
        delegate.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }

    override fun createDataSource(): HttpDataSource {
        val upstream = delegate.createDataSource()
        return LoggingHttpDataSource(upstream)
    }
}

@UnstableApi
private class LoggingHttpDataSource(
    private val upstream: HttpDataSource
) : HttpDataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        logOpen(dataSpec.uri)
        return length
    }

    private fun logOpen(uri: Uri?) {
        val responseCode = runCatching { upstream.responseCode }.getOrNull()
        val headers = runCatching { upstream.responseHeaders }.getOrNull().orEmpty()
        val contentType =
            headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.firstOrNull()

        Media3Diagnostics.logHttpOpen(uri?.toString(), responseCode, contentType)
    }
}
