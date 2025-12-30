package com.xyoye.common_component.bilibili.net

import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.network.helper.DecompressInterceptor
import com.xyoye.common_component.network.helper.DynamicBaseUrlInterceptor
import com.xyoye.common_component.network.helper.LoggerInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object BilibiliOkHttpClientFactory {
    fun create(storageKey: String): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(BilibiliCookieJarStore(storageKey))
            .addInterceptor(BilibiliHeaderInterceptor())
            .addInterceptor(DecompressInterceptor())
            .addInterceptor(DynamicBaseUrlInterceptor())
            .addInterceptor(LoggerInterceptor().retrofit())
            .build()

    private class BilibiliHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            val builder = original.newBuilder()

            if (original.header(BilibiliHeaders.HEADER_USER_AGENT).isNullOrEmpty()) {
                builder.header(BilibiliHeaders.HEADER_USER_AGENT, BilibiliHeaders.USER_AGENT)
            }
            if (original.header(BilibiliHeaders.HEADER_REFERER).isNullOrEmpty()) {
                val path = original.url.encodedPath
                val referer =
                    when (path) {
                        "/pgc/player/web/v2/playurl",
                        "/pgc/player/web/playurl" -> {
                            val epId = original.url.queryParameter("ep_id")
                            epId?.takeIf { it.isNotBlank() }?.let { "https://www.bilibili.com/bangumi/play/ep$it" }
                        }
                        else -> null
                    } ?: BilibiliHeaders.REFERER
                builder.header(BilibiliHeaders.HEADER_REFERER, referer)
            }
            if (original.header(BilibiliHeaders.HEADER_ACCEPT_ENCODING).isNullOrEmpty()) {
                builder.header(BilibiliHeaders.HEADER_ACCEPT_ENCODING, BilibiliHeaders.ACCEPT_ENCODING)
            }

            return chain.proceed(builder.build())
        }
    }
}
