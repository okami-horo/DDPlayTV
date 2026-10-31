package com.xyoye.common_component.network.tmdb

import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.network.service.TmdbService
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.core_system_component.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class TmdbFailure(
    val category: String,
    val retryAfterMs: Long? = null
) : IOException(category)

/** Dedicated host and client: no storage, Dandanplay auth, logging or image interceptors. */
object TmdbClient {
    val configured: Boolean get() = BuildConfig.TMDB_READ_ACCESS_TOKEN.isNotBlank()
    private val permits = Semaphore(2)
    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .cache(Cache(File(BaseApplication.getAppContext().cacheDir, "tmdb-http"), 16L * 1024 * 1024))
            .addInterceptor { chain ->
                if (!configured) throw TmdbFailure("NOT_CONFIGURED")
                val request = chain.request()
                if (request.url.host != "api.themoviedb.org" || !request.url.isHttps) throw TmdbFailure("INVALID_HOST")
                chain.proceed(request.newBuilder().header("Authorization", "Bearer ${BuildConfig.TMDB_READ_ACCESS_TOKEN}").build())
            }.build()
    }
    val service: TmdbService by lazy {
        Retrofit
            .Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(JsonHelper.MO_SHI))
            .build()
            .create(TmdbService::class.java)
    }

    suspend fun <T> request(block: suspend () -> T): T =
        permits.withPermit {
            var attempt = 0
            while (true) {
                try {
                    return@withPermit block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: HttpException) {
                    val category =
                        when (error.code()) {
                            401, 403 -> "AUTHENTICATION"
                            404 -> "NOT_FOUND"
                            429 -> "RATE_LIMIT"
                            in 500..599 -> "SERVER"
                            else -> "INVALID_RESPONSE"
                        }
                    val retry =
                        error
                            .response()
                            ?.headers()
                            ?.get("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1000)
                    // Long server cooldowns are surfaced for explicit retry rather than shortened.
                    if (attempt++ == 0 && (category == "SERVER" || category == "RATE_LIMIT") && (retry == null || retry <= 30_000)) {
                        delay((retry ?: 1000).coerceAtLeast(0))
                    } else {
                        throw TmdbFailure(category, retry)
                    }
                } catch (error: TmdbFailure) {
                    throw error
                } catch (error: IOException) {
                    if (attempt++ == 0) delay(500) else throw TmdbFailure("NETWORK")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw TmdbFailure("NETWORK")
        }

    fun clearHttpCache() {
        client.cache?.evictAll()
    }
}
