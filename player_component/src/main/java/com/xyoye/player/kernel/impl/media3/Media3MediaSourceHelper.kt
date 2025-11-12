package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.utils.PathHelper
import java.util.Locale

object Media3MediaSourceHelper {

    private val appContext get() = BaseApplication.getAppContext()
    private lateinit var cache: Cache

    private val userAgent: String = Util.getUserAgent(
        appContext,
        appContext.applicationInfo.name
    )

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)

    fun getMediaSource(
        uri: String,
        headers: Map<String, String>? = null,
        isCacheEnabled: Boolean = false
    ): MediaSource {
        val contentUri = Uri.parse(uri)
        val mediaItem = MediaItem.fromUri(contentUri)

        headers?.let { applyHeaders(it) }

        val dataSourceFactory = if (isCacheEnabled) {
            cacheDataSource()
        } else {
            DefaultDataSource.Factory(appContext, httpFactory)
        }

        return when (inferContentType(uri)) {
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_SS -> SsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    private fun cacheDataSource(): CacheDataSource.Factory {
        if (!this::cache.isInitialized) {
            cache = SimpleCache(
                PathHelper.getPlayCacheDirectory(),
                LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
                StandaloneDatabaseProvider(appContext)
            )
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, httpFactory))
    }

    private fun inferContentType(fileName: String): Int {
        val lower = fileName.lowercase(Locale.getDefault())
        return when {
            lower.contains(".mpd") -> C.CONTENT_TYPE_DASH
            lower.contains(".m3u8") -> C.CONTENT_TYPE_HLS
            lower.matches(".*\\.ism(l)?(/manifest(\\(.+\\))?)?".toRegex()) -> C.CONTENT_TYPE_SS
            else -> C.CONTENT_TYPE_OTHER
        }
    }

    private fun applyHeaders(headers: Map<String, String>) {
        headers.entries.find { it.key.equals("User-Agent", ignoreCase = true) }?.let {
            httpFactory.setUserAgent(it.value)
        }
        httpFactory.setDefaultRequestProperties(headers)
    }

    fun setCache(cacheInstance: Cache) {
        cache = cacheInstance
    }
}
