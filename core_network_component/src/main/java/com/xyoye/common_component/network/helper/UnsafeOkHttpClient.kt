package com.xyoye.common_component.network.helper

import android.annotation.SuppressLint
import com.xyoye.core_network_component.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Created by xyoye on 2021/5/2.
 *
 * 忽略证书验证的OkHttpClient
 */
@SuppressLint("CustomX509TrustManager")
object UnsafeOkHttpClient {
    private val unSafeTrustManager =
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            @Throws(CertificateException::class)
            override fun checkClientTrusted(
                chain: Array<X509Certificate>,
                authType: String
            ) {
            }

            @SuppressLint("TrustAllX509TrustManager")
            @Throws(CertificateException::class)
            override fun checkServerTrusted(
                chain: Array<X509Certificate>,
                authType: String
            ) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

    private val sslContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(unSafeTrustManager), null)
        }

    val client: OkHttpClient by lazy {
        val cookieStore =
            object : CookieJar {
                private val store = mutableMapOf<String, MutableList<Cookie>>()

                override fun saveFromResponse(
                    url: HttpUrl,
                    cookies: List<Cookie>
                ) {
                    if (cookies.isEmpty()) return
                    store[url.host] = cookies.toMutableList()
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
            }
        val builder =
            OkHttpClient
                .Builder()
                .sslSocketFactory(sslContext.socketFactory, unSafeTrustManager)
                .hostnameVerifier { _, _ -> true }
                .cookieJar(cookieStore)
                .addNetworkInterceptor(RedirectAuthorizationInterceptor())
        if (BuildConfig.DEBUG) {
            builder.addNetworkInterceptor(LoggerInterceptor().webDav())
        }
        return@lazy builder.build()
    }
}
