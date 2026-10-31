package com.xyoye.common_component.network

import com.xyoye.common_component.network.service.AlistService
import com.xyoye.common_component.network.service.BaiduPanService
import com.xyoye.common_component.network.service.Cloud115Service
import com.xyoye.common_component.network.service.DanDanService
import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.network.service.Open115Service
import com.xyoye.common_component.network.service.RemoteService
import com.xyoye.common_component.network.service.ScreencastService
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RetrofitManagerProviderTest {
    @After
    fun tearDown() {
        RetrofitManager.resetServiceProvider()
    }

    @Test
    fun services_areReadFromReplacedProvider() {
        val provider = FakeRetrofitServiceProvider()

        RetrofitManager.replaceServiceProvider(provider)

        assertSame(provider.danDanService, RetrofitManager.danDanService)
        assertSame(provider.extendedService, RetrofitManager.extendedService)
        assertSame(provider.remoteService, RetrofitManager.remoteService)
        assertSame(provider.screencastService, RetrofitManager.screencastService)
        assertSame(provider.alistService, RetrofitManager.alistService)
        assertSame(provider.baiduPanService, RetrofitManager.baiduPanService)
        assertSame(provider.open115Service, RetrofitManager.open115Service)
        assertSame(provider.cloud115Service, RetrofitManager.cloud115Service)
    }

    @Test
    fun createService_delegatesToCurrentProvider() {
        val provider = FakeRetrofitServiceProvider()
        val client = OkHttpClient()

        RetrofitManager.replaceServiceProvider(provider)
        val service = RetrofitManager.createService("https://example.com/", client, DanDanService::class.java)

        assertSame(provider.lastCreatedService, service)
        assertEquals("https://example.com/", provider.lastBaseUrl)
        assertSame(client, provider.lastClient)
        assertSame(DanDanService::class.java, provider.lastServiceClass)
    }

    @Test
    fun resetServiceProvider_restoresDefaultProvider() {
        val provider = FakeRetrofitServiceProvider()

        RetrofitManager.replaceServiceProvider(provider)
        assertSame(provider, RetrofitServiceLocator.currentProvider())

        RetrofitManager.resetServiceProvider()

        val resetProvider = RetrofitServiceLocator.currentProvider()
        assertNotSame(provider, resetProvider)
        assertTrue(resetProvider is DefaultRetrofitServiceProvider)
    }

    private class FakeRetrofitServiceProvider : RetrofitServiceProvider {
        override val danDanService: DanDanService = createServiceProxy(DanDanService::class.java)
        override val extendedService: ExtendedService = createServiceProxy(ExtendedService::class.java)
        override val remoteService: RemoteService = createServiceProxy(RemoteService::class.java)
        override val screencastService: ScreencastService = createServiceProxy(ScreencastService::class.java)
        override val alistService: AlistService = createServiceProxy(AlistService::class.java)
        override val baiduPanService: BaiduPanService = createServiceProxy(BaiduPanService::class.java)
        override val open115Service: Open115Service = createServiceProxy(Open115Service::class.java)
        override val cloud115Service: Cloud115Service = createServiceProxy(Cloud115Service::class.java)

        var lastBaseUrl: String? = null
        var lastClient: OkHttpClient? = null
        var lastServiceClass: Class<*>? = null
        var lastCreatedService: Any? = null

        override fun <T> createService(
            baseUrl: String,
            client: OkHttpClient,
            service: Class<T>
        ): T {
            lastBaseUrl = baseUrl
            lastClient = client
            lastServiceClass = service
            return createServiceProxy(service).also { created ->
                lastCreatedService = created
            }
        }
    }
}

private fun <T> createServiceProxy(serviceClass: Class<T>): T {
    lateinit var proxyReference: Any
    proxyReference =
        Proxy.newProxyInstance(serviceClass.classLoader, arrayOf(serviceClass)) { _, method, args ->
            when (method.name) {
                "toString" -> "Fake${serviceClass.simpleName}"
                "hashCode" -> serviceClass.hashCode()
                "equals" -> proxyReference === args?.firstOrNull()
                else -> throw UnsupportedOperationException("Fake service does not execute API calls")
            }
        }
    @Suppress("UNCHECKED_CAST")
    return proxyReference as T
}
