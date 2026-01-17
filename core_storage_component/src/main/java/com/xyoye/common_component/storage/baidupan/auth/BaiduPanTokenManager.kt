package com.xyoye.common_component.storage.baidupan.auth

import com.xyoye.common_component.config.BaiduPanOpenApiConfig
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.network.request.PassThroughException
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanOAuthError
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanTokenResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BaiduPanTokenManager(
    private val storageKey: String
) {
    companion object {
        private val mutexMap: MutableMap<String, Mutex> = ConcurrentHashMap()
        private val refreshAheadMs: Long = TimeUnit.MINUTES.toMillis(10)

        private fun refreshMutex(storageKey: String): Mutex = mutexMap.getOrPut(storageKey) { Mutex() }
    }

    fun clearAuth() = BaiduPanAuthStore.clear(storageKey)

    suspend fun requireAccessToken(
        forceRefresh: Boolean = false
    ): String {
        val state = BaiduPanAuthStore.read(storageKey)
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
                val state = BaiduPanAuthStore.read(storageKey)
                val now = System.currentTimeMillis()

                val accessToken = state.accessToken?.takeIf { it.isNotBlank() }
                if (!forceRefresh && accessToken != null && now + refreshAheadMs < state.expiresAtMs) {
                    return@withLock accessToken
                }

                val refreshToken =
                    state.refreshToken?.takeIf { it.isNotBlank() }
                        ?: throw BaiduPanReAuthRequiredException("百度网盘登录已失效，请重新扫码授权")

                if (!BaiduPanOpenApiConfig.isConfigured()) {
                    throw BaiduPanNotConfiguredException("未配置百度网盘开放平台密钥")
                }

                runCatching {
                    val response =
                        Retrofit.baiduPanService.oauthToken(
                            baseUrl = Api.BAIDU_OAUTH,
                            grantType = "refresh_token",
                            code = null,
                            refreshToken = refreshToken,
                            clientId = BaiduPanOpenApiConfig.clientId,
                            clientSecret = BaiduPanOpenApiConfig.clientSecret,
                        )

                    val payload =
                        response.body()?.string()
                            ?: response.errorBody()?.string()
                            ?: ""

                    val oauthError = JsonHelper.parseJson<BaiduPanOAuthError>(payload)
                    if (oauthError != null && oauthError.error.isNullOrBlank().not()) {
                        if (oauthError.error == "invalid_client") {
                            throw BaiduPanNotConfiguredException(
                                oauthError.errorDescription?.takeIf { it.isNotBlank() } ?: "百度网盘开放平台密钥无效",
                            )
                        }

                        clearAuth()
                        throw BaiduPanReAuthRequiredException(
                            oauthError.errorDescription?.takeIf { it.isNotBlank() }
                                ?: oauthError.error?.takeIf { it.isNotBlank() }
                                ?: "百度网盘登录已失效，请重新扫码授权",
                        )
                    }

                    val token = JsonHelper.parseJson<BaiduPanTokenResponse>(payload)
                    if (token == null || token.accessToken.isBlank() || token.refreshToken.isBlank() || token.expiresIn <= 0) {
                        throw IllegalStateException("Unexpected token payload: code=${response.code()} payload=$payload")
                    }

                    val updatedAtMs = System.currentTimeMillis()
                    val expiresAtMs = updatedAtMs + token.expiresIn * 1000L

                    BaiduPanAuthStore.writeTokens(
                        storageKey = storageKey,
                        accessToken = token.accessToken,
                        expiresAtMs = expiresAtMs,
                        refreshToken = token.refreshToken,
                        scope = token.scope,
                        updatedAtMs = updatedAtMs,
                    )

                    token.accessToken
                }.getOrElse { t ->
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "BaiduPanTokenManager",
                        "refreshAccessToken",
                        "storageKey=$storageKey forceRefresh=$forceRefresh",
                    )

                    when (t) {
                        is BaiduPanNotConfiguredException -> throw t
                        is BaiduPanReAuthRequiredException -> throw t
                        is PassThroughException -> throw t
                        else -> {
                            clearAuth()
                            throw BaiduPanReAuthRequiredException("百度网盘登录已失效，请重新扫码授权", t)
                        }
                    }
                }
            }
}

class BaiduPanReAuthRequiredException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause),
    PassThroughException

class BaiduPanNotConfiguredException(
    message: String
) : RuntimeException(message),
    PassThroughException
