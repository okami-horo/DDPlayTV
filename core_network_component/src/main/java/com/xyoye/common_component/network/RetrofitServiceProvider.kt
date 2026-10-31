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
import com.xyoye.common_component.network.helper.OkHttpClientConfig
import com.xyoye.common_component.network.helper.OkHttpClientFactory
import com.xyoye.common_component.network.helper.SignatureInterceptor
import com.xyoye.common_component.network.service.AlistService
import com.xyoye.common_component.network.service.BaiduPanService
import com.xyoye.common_component.network.service.Cloud115Service
import com.xyoye.common_component.network.service.DanDanService
import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.network.service.Open115Service
import com.xyoye.common_component.network.service.RemoteService
import com.xyoye.common_component.network.service.ScreencastService
import com.xyoye.common_component.utils.JsonHelper
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

interface RetrofitServiceProvider {
    val danDanService: DanDanService
    val extendedService: ExtendedService
    val remoteService: RemoteService
    val screencastService: ScreencastService
    val alistService: AlistService
    val baiduPanService: BaiduPanService
    val open115Service: Open115Service
    val cloud115Service: Cloud115Service

    fun <T> createService(
        baseUrl: String,
        client: OkHttpClient,
        service: Class<T>
    ): T
}

internal class DefaultRetrofitServiceProvider : RetrofitServiceProvider {
    private val danDanClient: OkHttpClient by lazy {
        OkHttpClientFactory.create(
            OkHttpClientConfig(
                interceptors =
                    listOf(
                        SignatureInterceptor(),
                        DeveloperCertificateInterceptor(),
                        AgentInterceptor(),
                        AuthInterceptor(),
                        ForbiddenErrorInterceptor(),
                        DecompressInterceptor(),
                        BackupDomainInterceptor(),
                        LoggerInterceptor().retrofit(),
                    ),
            ),
        )
    }

    private val commonClient: OkHttpClient by lazy {
        OkHttpClientFactory.create(
            OkHttpClientConfig(
                interceptors =
                    listOf(
                        AgentInterceptor(),
                        DecompressInterceptor(),
                        DynamicBaseUrlInterceptor(),
                        LoggerInterceptor().retrofit(),
                    ),
            ),
        )
    }

    private val moshiConverterFactory = MoshiConverterFactory.create(JsonHelper.MO_SHI)

    override val danDanService: DanDanService by lazy {
        createService(Api.DAN_DAN_OPEN, danDanClient, DanDanService::class.java)
    }

    override val extendedService: ExtendedService by lazy {
        createService(Api.PLACEHOLDER, commonClient, ExtendedService::class.java)
    }

    override val remoteService: RemoteService by lazy {
        createService(Api.PLACEHOLDER, commonClient, RemoteService::class.java)
    }

    override val screencastService: ScreencastService by lazy {
        createService(Api.PLACEHOLDER, commonClient, ScreencastService::class.java)
    }

    override val alistService: AlistService by lazy {
        createService(Api.PLACEHOLDER, commonClient, AlistService::class.java)
    }

    override val baiduPanService: BaiduPanService by lazy {
        createService(Api.PLACEHOLDER, commonClient, BaiduPanService::class.java)
    }

    override val open115Service: Open115Service by lazy {
        createService(Api.PLACEHOLDER, commonClient, Open115Service::class.java)
    }

    override val cloud115Service: Cloud115Service by lazy {
        createService(Api.PLACEHOLDER, commonClient, Cloud115Service::class.java)
    }

    override fun <T> createService(
        baseUrl: String,
        client: OkHttpClient,
        service: Class<T>
    ): T =
        Retrofit
            .Builder()
            .addConverterFactory(moshiConverterFactory)
            .client(client)
            .baseUrl(baseUrl)
            .build()
            .create(service)
}

internal object RetrofitServiceLocator {
    @Volatile
    private var provider: RetrofitServiceProvider = DefaultRetrofitServiceProvider()

    fun currentProvider(): RetrofitServiceProvider = provider

    fun replaceProvider(newProvider: RetrofitServiceProvider) {
        provider = newProvider
    }

    fun resetProvider() {
        provider = DefaultRetrofitServiceProvider()
    }
}
