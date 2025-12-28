package com.xyoye.common_component.network

import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.network.helper.AgentInterceptor
import com.xyoye.common_component.network.helper.AuthInterceptor
import com.xyoye.common_component.network.helper.BackupDomainInterceptor
import com.xyoye.common_component.network.helper.DecompressInterceptor
import com.xyoye.common_component.network.helper.DeveloperCertificateInterceptor
import com.xyoye.common_component.network.helper.DynamicBaseUrlInterceptor
import com.xyoye.common_component.network.helper.ForbiddenErrorInterceptor
import com.xyoye.common_component.network.helper.LoggerInterceptor
import com.xyoye.common_component.network.helper.SignatureInterceptor
import com.xyoye.common_component.network.service.AlistService
import com.xyoye.common_component.network.service.BilibiliService
import com.xyoye.common_component.network.service.DanDanService
import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.network.service.MagnetService
import com.xyoye.common_component.network.service.RemoteService
import com.xyoye.common_component.network.service.ScreencastService
import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.bilibili.net.BilibiliHeaders
import com.xyoye.common_component.utils.JsonHelper
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Created by xyoye on 2020/4/14.
 */

class Retrofit private constructor() {
    companion object {
        val danDanService: DanDanService by lazy { Holder.instance.danDanService }
        val extendedService: ExtendedService by lazy { Holder.instance.extendedService }
        val remoteService: RemoteService by lazy { Holder.instance.remoteService }
        val magnetService: MagnetService by lazy { Holder.instance.magnetService }
        val screencastService: ScreencastService by lazy { Holder.instance.screencastService }
        val alistService: AlistService by lazy { Holder.instance.alistService }

        fun bilibiliService(storageKey: String): BilibiliService = Holder.instance.bilibiliService(storageKey)
    }

    private object Holder {
        val instance = Retrofit()
    }

    private val danDanClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(SignatureInterceptor())
            .addInterceptor(DeveloperCertificateInterceptor())
            .addInterceptor(AgentInterceptor())
            .addInterceptor(AuthInterceptor())
            .addInterceptor(ForbiddenErrorInterceptor())
            .addInterceptor(DecompressInterceptor())
            .addInterceptor(BackupDomainInterceptor())
            .addInterceptor(LoggerInterceptor().retrofit())
            .build()
    }

    private val commonClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(AgentInterceptor())
            .addInterceptor(DecompressInterceptor())
            .addInterceptor(DynamicBaseUrlInterceptor())
            .addInterceptor(LoggerInterceptor().retrofit())
            .build()
    }

    private val bilibiliServices = ConcurrentHashMap<String, BilibiliService>()

    private val moshiConverterFactory = MoshiConverterFactory.create(JsonHelper.MO_SHI)

    private val danDanService: DanDanService by lazy {
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(danDanClient)
            .baseUrl(Api.DAN_DAN_OPEN)
            .build()
            .create(DanDanService::class.java)
    }

    private val magnetService: MagnetService by lazy {
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(commonClient)
            .baseUrl(Api.DAN_DAN_RES)
            .build()
            .create(MagnetService::class.java)
    }

    private val extendedService: ExtendedService by lazy {
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(commonClient)
            .baseUrl(Api.PLACEHOLDER)
            .build()
            .create(ExtendedService::class.java)
    }

    private val remoteService: RemoteService by lazy {
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(commonClient)
            .baseUrl(Api.PLACEHOLDER)
            .build()
            .create(RemoteService::class.java)
    }

    private val screencastService: ScreencastService by lazy {
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(commonClient)
            .baseUrl(Api.PLACEHOLDER)
            .build()
            .create(ScreencastService::class.java)
    }

    private val alistService: AlistService by lazy {
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(commonClient)
            .baseUrl(Api.PLACEHOLDER)
            .build()
            .create(AlistService::class.java)
    }

    private fun bilibiliService(storageKey: String): BilibiliService =
        bilibiliServices.getOrPut(storageKey) {
            Retrofit
                .Builder()
                .addConverterFactory(moshiConverterFactory)
                .client(buildBilibiliClient(storageKey))
                .baseUrl(Api.PLACEHOLDER)
                .build()
                .create(BilibiliService::class.java)
        }

    private fun buildBilibiliClient(storageKey: String): OkHttpClient =
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
                builder.header(BilibiliHeaders.HEADER_REFERER, BilibiliHeaders.REFERER)
            }
            if (original.header(BilibiliHeaders.HEADER_ACCEPT_ENCODING).isNullOrEmpty()) {
                builder.header(BilibiliHeaders.HEADER_ACCEPT_ENCODING, BilibiliHeaders.ACCEPT_ENCODING)
            }

            return chain.proceed(builder.build())
        }
    }
}
