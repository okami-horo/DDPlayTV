package com.xyoye.common_component.storage.open115.auth

import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.network.request.PassThroughException
import com.xyoye.common_component.utils.ErrorReportHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class Open115TokenManager(
    private val storageKey: String
) {
    companion object {
        private val mutexMap: MutableMap<String, Mutex> = ConcurrentHashMap()
        private val refreshAheadMs: Long = TimeUnit.MINUTES.toMillis(10)

        private fun refreshMutex(storageKey: String): Mutex = mutexMap.getOrPut(storageKey) { Mutex() }
    }

    fun clearAuth() = Open115AuthStore.clear(storageKey)

    suspend fun requireAccessToken(
        forceRefresh: Boolean = false
    ): String {
        val state = Open115AuthStore.read(storageKey)
        val now = System.currentTimeMillis()
        val accessToken = state.accessToken?.takeIf { it.isNotBlank() }

        if (!forceRefresh && accessToken != null && now + refreshAheadMs < state.expiresAtMs) {
            return accessToken
        }

        return refreshAccessToken(forceRefresh = forceRefresh)
    }

    suspend fun refreshAccessToken(
        forceRefresh: Boolean = false
    ): String =
        refreshMutex(storageKey)
            .withLock {
                val state = Open115AuthStore.read(storageKey)
                val now = System.currentTimeMillis()

                val accessToken = state.accessToken?.takeIf { it.isNotBlank() }
                if (!forceRefresh && accessToken != null && now + refreshAheadMs < state.expiresAtMs) {
                    return@withLock accessToken
                }

                val refreshToken =
                    state.refreshToken?.takeIf { it.isNotBlank() }
                        ?: throw Open115NotConfiguredException("请先配置 115 Open token")

                runCatching {
                    val response =
                        Retrofit.open115Service.refreshToken(
                            baseUrl = Api.OPEN_115_PASSPORT_API,
                            refreshToken = refreshToken,
                        )

                    if (response.code != 0) {
                        val detail =
                            response.error?.takeIf { it.isNotBlank() }
                                ?: response.message?.takeIf { it.isNotBlank() }
                        val msg = detail ?: "115 Open 授权已失效，请更新 token（code=${response.code}）"
                        throw Open115ReAuthRequiredException(msg)
                    }

                    val token = response.data
                    if (token == null || token.accessToken.isBlank() || token.refreshToken.isBlank() || token.expiresIn <= 0) {
                        throw IllegalStateException("Unexpected refresh token payload: code=${response.code}")
                    }

                    val updatedAtMs = System.currentTimeMillis()
                    val expiresAtMs = updatedAtMs + token.expiresIn * 1000L

                    Open115AuthStore.writeTokens(
                        storageKey = storageKey,
                        accessToken = token.accessToken,
                        expiresAtMs = expiresAtMs,
                        refreshToken = token.refreshToken,
                        updatedAtMs = updatedAtMs,
                    )

                    token.accessToken
                }.getOrElse { t ->
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "Open115TokenManager",
                        "refreshAccessToken",
                        "storageKey=$storageKey forceRefresh=$forceRefresh",
                    )

                    when (t) {
                        is Open115NotConfiguredException -> throw t
                        is Open115ReAuthRequiredException -> throw t
                        is PassThroughException -> throw t
                        else -> throw Open115ReAuthRequiredException("115 Open 授权已失效，请更新 token 或稍后重试", t)
                    }
                }
            }
}

