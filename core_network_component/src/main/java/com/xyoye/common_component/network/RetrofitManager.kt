package com.xyoye.common_component.network

import okhttp3.OkHttpClient

/**
 * Created by xyoye on 2020/4/14.
 */

class RetrofitManager private constructor() {
    companion object {
        val danDanService get() = provider().danDanService
        val extendedService get() = provider().extendedService
        val remoteService get() = provider().remoteService
        val screencastService get() = provider().screencastService
        val alistService get() = provider().alistService
        val baiduPanService get() = provider().baiduPanService
        val open115Service get() = provider().open115Service
        val cloud115Service get() = provider().cloud115Service

        fun replaceServiceProvider(provider: RetrofitServiceProvider) {
            RetrofitServiceLocator.replaceProvider(provider)
        }

        fun resetServiceProvider() {
            RetrofitServiceLocator.resetProvider()
        }

        fun <T> createService(
            baseUrl: String,
            client: OkHttpClient,
            service: Class<T>
        ): T = provider().createService(baseUrl, client, service)

        private fun provider(): RetrofitServiceProvider = RetrofitServiceLocator.currentProvider()
    }
}
