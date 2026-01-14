package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.network.request.PassThroughException
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.storage.open115.auth.Open115NotConfiguredException
import com.xyoye.common_component.storage.open115.auth.Open115ReAuthRequiredException
import com.xyoye.common_component.storage.open115.auth.Open115TokenManager
import com.xyoye.common_component.storage.open115.net.Open115Headers
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.data.open115.Open115DownUrlResponse
import com.xyoye.data_component.data.open115.Open115FolderInfoResponse
import com.xyoye.data_component.data.open115.Open115ListFilesResponse
import com.xyoye.data_component.data.open115.Open115ProApiResponse
import com.xyoye.data_component.data.open115.Open115RefreshTokenResponse
import com.xyoye.data_component.data.open115.Open115SearchResponse
import com.xyoye.data_component.data.open115.Open115UserInfoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Open115Repository(
    private val storageKey: String
) : BaseRepository() {
    private val tokenManager by lazy { Open115TokenManager(storageKey) }

    fun isAuthorized(): Boolean = Open115AuthStore.read(storageKey).isAuthorized()

    suspend fun userInfo(): Result<Open115UserInfoResponse> =
        requestProApi(reason = "userInfo") { accessToken ->
            Retrofit.open115Service.userInfo(
                baseUrl = Api.OPEN_115_PRO_API,
                authorization = Open115Headers.bearer(accessToken),
            )
        }

    suspend fun listFiles(
        cid: String,
        limit: Int,
        offset: Int,
        asc: String? = null,
        order: String? = null,
        showDir: String? = "1"
    ): Result<Open115ListFilesResponse> =
        requestProApi(
            reason = "listFiles",
            extraInfo = "cid=$cid limit=$limit offset=$offset",
        ) { accessToken ->
            Retrofit.open115Service.listFiles(
                baseUrl = Api.OPEN_115_PRO_API,
                authorization = Open115Headers.bearer(accessToken),
                cid = cid,
                limit = limit,
                offset = offset,
                asc = asc,
                order = order,
                showDir = showDir,
            )
        }

    suspend fun searchFiles(
        searchValue: String,
        cid: String? = null,
        type: String? = null,
        fc: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Result<Open115SearchResponse> =
        requestProApi(
            reason = "searchFiles",
            extraInfo = "keywordLength=${searchValue.length} cid=$cid limit=${limit ?: -1} offset=${offset ?: -1}",
        ) { accessToken ->
            Retrofit.open115Service.searchFiles(
                baseUrl = Api.OPEN_115_PRO_API,
                authorization = Open115Headers.bearer(accessToken),
                searchValue = searchValue,
                cid = cid,
                type = type,
                fc = fc,
                limit = limit,
                offset = offset,
            )
        }

    suspend fun downUrl(
        pickCode: String,
        userAgent: String = Open115Headers.USER_AGENT
    ): Result<Open115DownUrlResponse> =
        requestProApi(
            reason = "downUrl",
            extraInfo = "pickCodeLength=${pickCode.length} uaLength=${userAgent.length}",
        ) { accessToken ->
            Retrofit.open115Service.downUrl(
                baseUrl = Api.OPEN_115_PRO_API,
                authorization = Open115Headers.bearer(accessToken),
                userAgent = userAgent,
                pickCode = pickCode,
            )
        }

    suspend fun folderGetInfo(
        fileId: String
    ): Result<Open115FolderInfoResponse> =
        requestProApi(
            reason = "folderGetInfo",
            extraInfo = "fileId=$fileId",
        ) { accessToken ->
            Retrofit.open115Service.folderGetInfo(
                baseUrl = Api.OPEN_115_PRO_API,
                authorization = Open115Headers.bearer(accessToken),
                fileId = fileId,
            )
        }

    private suspend fun <T : Open115ProApiResponse> requestProApi(
        reason: String,
        extraInfo: String = "",
        call: suspend (accessToken: String) -> T
    ): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                var accessToken = tokenManager.requireAccessToken(forceRefresh = false)
                var authRetried = false

                var success: T? = null

                while (success == null) {
                    val response = call(accessToken)
                    if (response.state) {
                        success = response
                        break
                    }

                    if (!authRetried && isAuthExpiredCode(response.code)) {
                        authRetried = true
                        accessToken = tokenManager.refreshAccessToken(forceRefresh = true)
                        continue
                    }

                    throw Open115ProApiException.from(code = response.code, message = response.message)
                }

                Result.success(success ?: throw IllegalStateException("Unreachable"))
            } catch (t: Throwable) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    t,
                    "Open115Repository",
                    reason,
                    "storageKey=$storageKey $extraInfo",
                )
                Result.failure(t)
            }
        }

    companion object {
        suspend fun userInfo(
            accessToken: String
        ): Result<Open115UserInfoResponse> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val trimmed = accessToken.trim()
                    if (trimmed.isBlank()) {
                        throw Open115NotConfiguredException("请先填写 access_token")
                    }

                    val response =
                        Retrofit.open115Service.userInfo(
                            baseUrl = Api.OPEN_115_PRO_API,
                            authorization = Open115Headers.bearer(trimmed),
                        )

                    if (!response.state) {
                        throw Open115ProApiException.from(code = response.code, message = response.message)
                    }

                    response
                }.onFailure { t ->
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "Open115Repository",
                        "userInfo",
                        "accessTokenLength=${accessToken.length}",
                    )
                }
            }

        suspend fun refreshToken(
            refreshToken: String
        ): Result<Open115RefreshTokenResponse> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val trimmed = refreshToken.trim()
                    if (trimmed.isBlank()) {
                        throw Open115NotConfiguredException("请先填写 refresh_token")
                    }

                    val response =
                        Retrofit.open115Service.refreshToken(
                            baseUrl = Api.OPEN_115_PASSPORT_API,
                            refreshToken = trimmed,
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

                    response
                }.onFailure { t ->
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "Open115Repository",
                        "refreshToken",
                        "refreshTokenLength=${refreshToken.length}",
                    )
                }
            }

        fun isAuthExpiredCode(code: Int): Boolean = code == 99 || code.toString().startsWith("401")
    }
}

class Open115ProApiException(
    val code: Int,
    val open115Message: String? = null,
    val hint: String? = null
) : RuntimeException(buildMessage(code, open115Message, hint)),
    PassThroughException {
    companion object {
        fun from(
            code: Int,
            message: String? = null
        ): Open115ProApiException {
            val hint =
                when {
                    Open115Repository.isAuthExpiredCode(code) -> "授权已失效，请更新 token"
                    else -> "115 Open 请求失败"
                }
            return Open115ProApiException(
                code = code,
                open115Message = message?.trim(),
                hint = hint,
            )
        }

        private fun buildMessage(
            code: Int,
            message: String?,
            hint: String?
        ): String {
            val base = hint ?: "115 Open 请求失败"
            val detail = message?.takeIf { it.isNotBlank() }
            return if (detail.isNullOrEmpty()) {
                "$base（code=$code）"
            } else {
                "$base：$detail（code=$code）"
            }
        }
    }
}
