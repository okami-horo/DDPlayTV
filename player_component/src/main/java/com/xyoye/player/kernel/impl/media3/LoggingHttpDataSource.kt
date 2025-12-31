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
        return try {
            val length = upstream.open(dataSpec)
            logOpen(
                uri = dataSpec.uri,
                responseCode = runCatching { upstream.responseCode }.getOrNull(),
                headers = runCatching { upstream.responseHeaders }.getOrNull(),
            )
            length
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            logOpen(
                uri = dataSpec.uri,
                responseCode = e.responseCode,
                headers = e.headerFields,
            )
            throw e
        } catch (e: Exception) {
            Media3Diagnostics.logHttpOpen(dataSpec.uri?.toString(), null, null)
            throw e
        }
    }

    private fun logOpen(
        uri: Uri?,
        responseCode: Int?,
        headers: Map<String, List<String>>?,
    ) {
        val resolvedHeaders = headers.orEmpty()
        val contentType =
            resolvedHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.firstOrNull()

        Media3Diagnostics.logHttpOpen(uri?.toString(), responseCode, contentType)
    }
}
