package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.utils.PathHelper
import java.util.Locale

@UnstableApi
object Media3MediaSourceHelper {
    private val appContext get() = BaseApplication.getAppContext()
    private lateinit var cache: Cache

    private val userAgent: String =
        Util.getUserAgent(
            appContext,
            appContext.applicationInfo.name,
        )

    fun createMediaSourceFactory(): MediaSource.Factory =
        object : MediaSource.Factory by DefaultMediaSourceFactory(appContext) {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource = getMediaSource(mediaItem)
        }

    fun getMediaSource(
        uri: String,
        headers: Map<String, String>? = null,
        isCacheEnabled: Boolean = false,
        mediaId: String? = null,
        subtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
    ): MediaSource =
        getMediaSource(
            MediaItem
                .Builder()
                .setUri(uri)
                .setSubtitleConfigurations(subtitles)
                .apply {
                    if (!mediaId.isNullOrBlank()) setMediaId(mediaId)
                }.build(),
            headers,
            isCacheEnabled
        )

    fun getMediaSource(
        mediaItem: MediaItem,
        headers: Map<String, String>? = null,
        isCacheEnabled: Boolean = false
    ): MediaSource {
        val local = requireNotNull(mediaItem.localConfiguration)
        val contentUri = local.uri
        val headersWithDefaults = headers?.toMap()
        val subtitleInput = Media3SubtitleInput(requestHeaders = headersWithDefaults, cacheEnabled = isCacheEnabled)
        val fontAttachmentSink = subtitleInput.beginMedia()
        val mediaItemBuilder = mediaItem.buildUpon().setTag(subtitleInput)
        val httpFactory =
            LoggingHttpDataSourceFactory()
                .setUserAgent(headersWithDefaults?.entries?.find { it.key.equals("User-Agent", true) }?.value ?: userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headersWithDefaults.orEmpty())

        val dataSourceFactory =
            if (isCacheEnabled) {
                cacheDataSource(httpFactory)
            } else {
                DefaultDataSource.Factory(appContext, httpFactory)
            }

        val resolvedContentType =
            if (local.mimeType != null) {
                C.CONTENT_TYPE_OTHER
            } else {
                resolveContentType(contentUri.toString(), contentUri, httpFactory)
            }

        when (resolvedContentType) {
            C.CONTENT_TYPE_DASH -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            C.CONTENT_TYPE_SS -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_SS)
            C.CONTENT_TYPE_HLS -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val parserFactory = LibassSubtitleParserFactory(subtitleInput)
        return DefaultMediaSourceFactory(dataSourceFactory, LibassExtractorsFactory(fontAttachmentSink, subtitleInput, parserFactory))
            .setSubtitleParserFactory(parserFactory)
            .createMediaSource(mediaItemBuilder.build())
    }

    private fun cacheDataSource(httpFactory: HttpDataSource.Factory): CacheDataSource.Factory {
        if (!this::cache.isInitialized) {
            cache =
                SimpleCache(
                    PathHelper.getPlayCacheDirectory(),
                    LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
                    StandaloneDatabaseProvider(appContext),
                )
        }
        return CacheDataSource
            .Factory()
            .setCache(cache)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, httpFactory))
    }

    private fun resolveContentType(
        rawUri: String,
        contentUri: Uri,
        httpFactory: HttpDataSource.Factory
    ): Int {
        val byExtension = inferContentType(rawUri)
        if (byExtension != C.CONTENT_TYPE_OTHER) {
            return byExtension
        }

        val contentType = sniffHttpContentType(contentUri, httpFactory)
        if (contentType != null && isHlsMime(contentType)) {
            Media3Diagnostics.logContentTypeOverride(rawUri, contentType, "hls_by_content_type")
            return C.CONTENT_TYPE_HLS
        }
        return byExtension
    }

    private fun sniffHttpContentType(
        contentUri: Uri,
        httpFactory: HttpDataSource.Factory
    ): String? {
        if (contentUri.scheme != "http" && contentUri.scheme != "https") {
            return null
        }
        val dataSource = httpFactory.createDataSource()
        val dataSpec =
            DataSpec
                .Builder()
                .setUri(contentUri)
                .setHttpMethod(DataSpec.HTTP_METHOD_HEAD)
                .build()
        return runCatching {
            try {
                dataSource.open(dataSpec)
                dataSource.responseHeaders.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
            } finally {
                dataSource.close()
            }
        }.getOrNull()
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

    private fun isHlsMime(contentType: String): Boolean {
        val lower = contentType.lowercase(Locale.getDefault())
        return lower.contains("application/vnd.apple.mpegurl") || lower.contains("application/x-mpegurl")
    }

    fun setCache(cacheInstance: Cache) {
        cache = cacheInstance
    }
}
