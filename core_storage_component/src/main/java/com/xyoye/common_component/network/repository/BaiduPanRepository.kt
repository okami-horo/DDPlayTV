package com.xyoye.common_component.network.repository

import com.xyoye.common_component.config.BaiduPanOpenApiConfig
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.network.request.PassThroughException
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanNotConfiguredException
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanReAuthRequiredException
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanTokenManager
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanDeviceCodeResponse
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanOAuthError
import com.xyoye.data_component.data.baidupan.oauth.BaiduPanTokenResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanErrnoResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanFileMetasResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanUinfoResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanFileItem
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanListResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.util.concurrent.TimeUnit

class BaiduPanRepository(
    private val storageKey: String
) : BaseRepository() {
    private val tokenManager by lazy { BaiduPanTokenManager(storageKey) }

    fun isAuthorized(): Boolean = BaiduPanAuthStore.read(storageKey).isAuthorized()

    suspend fun oauthDeviceCode(
        scope: String = DEFAULT_SCOPE
    ): Result<BaiduPanDeviceCodeResponse> =
        requestOAuth(reason = "oauthDeviceCode") {
            ensureConfigured()
            Retrofit.baiduPanService.oauthDeviceCode(
                baseUrl = Api.BAIDU_OAUTH,
                responseType = "device_code",
                clientId = BaiduPanOpenApiConfig.clientId,
                scope = scope,
            )
        }

    suspend fun oauthTokenByDeviceCode(
        deviceCode: String
    ): Result<BaiduPanTokenResponse> =
        requestOAuth(reason = "oauthTokenByDeviceCode") {
            ensureConfigured()
            Retrofit.baiduPanService.oauthToken(
                baseUrl = Api.BAIDU_OAUTH,
                grantType = "device_token",
                code = deviceCode,
                refreshToken = null,
                clientId = BaiduPanOpenApiConfig.clientId,
                clientSecret = BaiduPanOpenApiConfig.clientSecret,
            )
        }

    suspend fun xpanUinfo(
        vipVersion: String? = "v2"
    ): Result<BaiduPanUinfoResponse> =
        requestXpan(reason = "xpanUinfo") { accessToken ->
            Retrofit.baiduPanService.xpanUinfo(
                baseUrl = Api.BAIDU_PAN,
                method = "uinfo",
                accessToken = accessToken,
                vipVersion = vipVersion,
            )
        }.onSuccess { uinfo ->
            val uk = uinfo.uk
            if (uk != null && uk > 0) {
                BaiduPanAuthStore.writeProfile(
                    storageKey = storageKey,
                    uk = uk,
                    netdiskName = uinfo.netdiskName,
                    avatarUrl = uinfo.avatarUrl,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }

    suspend fun xpanList(
        dir: String,
        start: Int? = null,
        limit: Int? = null,
        order: String? = null,
        desc: Boolean? = null,
        web: Boolean = false
    ): Result<BaiduPanXpanListResponse> =
        requestXpan(reason = "xpanList") { accessToken ->
            Retrofit.baiduPanService.xpanFileList(
                baseUrl = Api.BAIDU_PAN,
                method = "list",
                accessToken = accessToken,
                dir = dir,
                order = order,
                desc = desc?.let { if (it) 1 else 0 },
                start = start,
                limit = limit,
                web = if (web) 1 else 0,
            )
        }

    suspend fun xpanSearch(
        dir: String?,
        key: String,
        recursion: Boolean = false,
        category: Int? = null,
        web: Boolean = false
    ): Result<BaiduPanXpanSearchResponse> =
        requestXpan(reason = "xpanSearch") { accessToken ->
            Retrofit.baiduPanService.xpanFileSearch(
                baseUrl = Api.BAIDU_PAN,
                method = "search",
                accessToken = accessToken,
                dir = dir,
                key = key,
                recursion = if (recursion) 1 else 0,
                category = category,
                web = if (web) 1 else 0,
            )
        }

    suspend fun search(
        dir: String?,
        keyword: String,
        recursion: Boolean = false,
        category: Int? = null,
        web: Boolean = false
    ): Result<List<BaiduPanXpanFileItem>> =
        xpanSearch(
            dir = dir,
            key = keyword,
            recursion = recursion,
            category = category,
            web = web,
        ).map { it.list.orEmpty() }

    suspend fun xpanFileMetas(
        fsIds: List<Long>,
        dlink: Boolean = true,
        needMedia: Boolean = false,
        detail: Boolean = false
    ): Result<BaiduPanFileMetasResponse> =
        requestXpan(reason = "xpanFileMetas") { accessToken ->
            Retrofit.baiduPanService.xpanFileMetas(
                baseUrl = Api.BAIDU_PAN,
                method = "filemetas",
                accessToken = accessToken,
                fsids = fsIds.toJsonArrayParam(),
                dlink = if (dlink) 1 else 0,
                needmedia = if (needMedia) 1 else 0,
                detail = if (detail) 1 else 0,
            )
        }

    private suspend inline fun <reified T> requestOAuth(
        reason: String,
        crossinline call: suspend () -> Response<ResponseBody>
    ): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = call()
                val payload =
                    response.body()?.string()
                        ?: response.errorBody()?.string()
                        ?: ""

                val oauthError = JsonHelper.parseJson<BaiduPanOAuthError>(payload)
                if (oauthError != null && oauthError.error.isNullOrBlank().not()) {
                    throw BaiduPanOAuthException.from(oauthError)
                }

                JsonHelper.parseJson<T>(payload)
                    ?: throw IllegalStateException("Unexpected oauth payload: code=${response.code()} payload=$payload")
            }.onFailure { t ->
                ErrorReportHelper.postCatchedExceptionWithContext(
                    t,
                    "BaiduPanRepository",
                    reason,
                    "storageKey=$storageKey",
                )
            }
        }

    private suspend fun <T : BaiduPanErrnoResponse> requestXpan(
        reason: String,
        call: suspend (accessToken: String) -> T
    ): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                var accessToken = tokenManager.requireAccessToken(forceRefresh = false)
                var authRetried = false
                var rateRetried = 0

                var success: T? = null

                while (success == null) {
                    val response = call(accessToken)
                    if (response.errno == 0) {
                        success = response
                        break
                    }

                    if (!authRetried && response.errno in AUTH_ERRNOS) {
                        authRetried = true
                        accessToken = tokenManager.refreshAccessToken(forceRefresh = true)
                        continue
                    }

                    if (response.errno in RATE_LIMIT_ERRNOS && rateRetried < MAX_RATE_RETRIES) {
                        delay(rateDelayMs(rateRetried))
                        rateRetried++
                        continue
                    }

                    throw BaiduPanXpanException.from(errno = response.errno, message = response.errmsg)
                }

                Result.success(success ?: throw IllegalStateException("Unreachable"))
            } catch (t: Throwable) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    t,
                    "BaiduPanRepository",
                    reason,
                    "storageKey=$storageKey",
                )
                Result.failure(t)
            }
        }

    private fun ensureConfigured() {
        if (!BaiduPanOpenApiConfig.isConfigured()) {
            throw BaiduPanNotConfiguredException("未配置百度网盘开放平台密钥")
        }
    }

    private fun List<Long>.toJsonArrayParam(): String =
        joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { it.toString() }

    private fun rateDelayMs(attempt: Int): Long {
        val base = TimeUnit.SECONDS.toMillis(1)
        return base * (attempt + 1)
    }

    companion object {
        private const val DEFAULT_SCOPE = "basic,netdisk"

        private val AUTH_ERRNOS: Set<Int> = setOf(-6, 31045)
        private val RATE_LIMIT_ERRNOS: Set<Int> = setOf(20012, 31034)
        private const val MAX_RATE_RETRIES = 2
    }
}

class BaiduPanOAuthException(
    val error: String,
    val errorDescription: String? = null
) : RuntimeException(buildMessage(error, errorDescription)),
    PassThroughException {
    companion object {
        fun from(model: BaiduPanOAuthError): BaiduPanOAuthException =
            BaiduPanOAuthException(
                error = model.error?.trim().orEmpty(),
                errorDescription = model.errorDescription?.trim(),
            )

        private fun buildMessage(
            error: String,
            description: String?
        ): String {
            val hint =
                when (error) {
                    "authorization_pending" -> "等待用户扫码确认"
                    "slow_down" -> "请求过于频繁，请稍后重试"
                    "access_denied" -> "用户拒绝授权"
                    "expired_token" -> "二维码已过期，请重新获取"
                    "invalid_client" -> "百度网盘开放平台密钥无效"
                    "invalid_grant" -> "授权已失效，请重新扫码授权"
                    else -> "百度网盘授权失败"
                }

            val detail = description?.takeIf { it.isNotBlank() }
            return if (detail.isNullOrEmpty()) {
                "$hint（error=$error）"
            } else {
                "$hint：$detail（error=$error）"
            }
        }
    }
}

class BaiduPanXpanException(
    val errno: Int,
    val baiduMessage: String? = null,
    val hint: String? = null
) : RuntimeException(buildMessage(errno, baiduMessage, hint)),
    PassThroughException {
    companion object {
        fun from(
            errno: Int,
            message: String? = null
        ): BaiduPanXpanException {
            val hint =
                when (errno) {
                    -6, 31045 -> "授权已失效，请重新扫码授权"
                    -7 -> "无权访问"
                    -9 -> "资源不存在"
                    20012, 31034 -> "请求过于频繁，请稍后重试"
                    else -> "百度网盘请求失败"
                }
            return BaiduPanXpanException(
                errno = errno,
                baiduMessage = message,
                hint = hint,
            )
        }

        private fun buildMessage(
            errno: Int,
            message: String?,
            hint: String?
        ): String {
            val base = hint ?: "百度网盘请求失败"
            val detail = message?.takeIf { it.isNotBlank() }
            return if (detail.isNullOrEmpty()) {
                "$base（errno=$errno）"
            } else {
                "$base：$detail（errno=$errno）"
            }
        }
    }
}
