package com.xyoye.common_component.bilibili.repository

import com.xyoye.common_component.bilibili.BilibiliPlayurlPreferencesMapper
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferences
import com.xyoye.common_component.bilibili.auth.BilibiliAuthStore
import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.wbi.BilibiliWbiSigner
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.request.RequestParams
import com.xyoye.common_component.network.repository.BaseRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData
import com.xyoye.data_component.data.bilibili.BilibiliJsonModel
import com.xyoye.data_component.data.bilibili.BilibiliNavData
import com.xyoye.data_component.data.bilibili.BilibiliPagelistItem
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodeGenerateData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodePollData
import okhttp3.ResponseBody
import java.net.SocketTimeoutException

class BilibiliRepository(
    private val storageKey: String,
) : BaseRepository() {
    private val service by lazy { Retrofit.bilibiliService(storageKey) }
    private val cookieJarStore by lazy { BilibiliCookieJarStore(storageKey) }

    fun isLoggedIn(): Boolean = cookieJarStore.isLoginCookiePresent()

    fun cookieHeaderOrNull(): String? = cookieJarStore.exportCookieHeader()

    suspend fun nav(): Result<BilibiliNavData> =
        requestBilibili {
            service.nav(BASE_API)
        }

    suspend fun qrcodeGenerate(): Result<BilibiliQrcodeGenerateData> =
        requestBilibili {
            service.qrcodeGenerate(BASE_PASSPORT)
        }

    suspend fun qrcodePoll(qrcodeKey: String): Result<BilibiliQrcodePollData> =
        requestBilibili {
            service.qrcodePoll(BASE_PASSPORT, qrcodeKey)
        }.onSuccess { data ->
            if (data.statusCode == 0 && !data.refreshToken.isNullOrEmpty()) {
                BilibiliAuthStore.updateFromCookies(
                    storageKey = storageKey,
                    cookieJarStore = cookieJarStore,
                    refreshToken = data.refreshToken,
                )
            }
        }

    suspend fun historyCursor(
        max: Long? = null,
        viewAt: Long? = null,
        business: String? = null,
        ps: Int = 30,
        type: String = "archive",
    ): Result<BilibiliHistoryCursorData> {
        val params: RequestParams = hashMapOf()
        max?.let { params["max"] = it }
        viewAt?.let { params["view_at"] = it }
        business?.let { params["business"] = it }
        params["ps"] = ps
        params["type"] = type

        return requestBilibili {
            service.historyCursor(BASE_API, params)
        }
    }

    suspend fun pagelist(bvid: String): Result<List<BilibiliPagelistItem>> =
        requestBilibili {
            service.pagelist(BASE_API, bvid)
        }

    suspend fun playurl(
        bvid: String,
        cid: Long,
        preferences: BilibiliPlaybackPreferences,
    ): Result<BilibiliPlayurlData> {
        val params: RequestParams = hashMapOf()
        params["bvid"] = bvid
        params["cid"] = cid

        params.putAll(BilibiliPlayurlPreferencesMapper.primaryParams(preferences))

        val signed =
            BilibiliWbiSigner.sign(params) {
                fetchWbiKeys()
            }

        return requestBilibili {
            service.playurl(BASE_API, signed)
        }.recoverTimeout("取流超时，请检查网络后重试")
    }

    suspend fun playurlFallbackOrNull(
        bvid: String,
        cid: Long,
        preferences: BilibiliPlaybackPreferences,
    ): Result<BilibiliPlayurlData>? {
        val fallback = BilibiliPlayurlPreferencesMapper.fallbackParamsOrNull(preferences) ?: return null
        val params: RequestParams = hashMapOf()
        params["bvid"] = bvid
        params["cid"] = cid
        params.putAll(fallback)
        val signed =
            BilibiliWbiSigner.sign(params) {
                fetchWbiKeys()
            }
        return requestBilibili {
            service.playurl(BASE_API, signed)
        }.recoverTimeout("取流超时，请检查网络后重试")
    }

    suspend fun danmakuXml(cid: Long): Result<ResponseBody> =
        runCatching {
            service.danmakuXml(BASE_COMMENT, cid)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )

    suspend fun danmakuListSo(cid: Long): Result<ResponseBody> =
        runCatching {
            service.danmakuListSo(BASE_API, cid)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )

    fun clear() {
        cookieJarStore.clear()
        BilibiliAuthStore.clear(storageKey)
    }

    private suspend fun fetchWbiKeys(): BilibiliWbiSigner.WbiKeys? {
        val nav =
            runCatching { service.nav(BASE_API) }
                .getOrNull()
                ?.also { ensureSuccess(it) }
                ?.successData
                ?: return null
        val imgKey = BilibiliWbiSigner.extractKeyFromUrl(nav.wbiImg?.imgUrl) ?: return null
        val subKey = BilibiliWbiSigner.extractKeyFromUrl(nav.wbiImg?.subUrl) ?: return null
        return BilibiliWbiSigner.WbiKeys(
            imgKey = imgKey,
            subKey = subKey,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun <T> requestBilibili(
        block: suspend () -> BilibiliJsonModel<T>
    ): Result<T> =
        request().doGet {
            val model = block()
            ensureSuccess(model)
            model.successData ?: throw BilibiliException.from(0, "响应数据为空")
        }

    private fun ensureSuccess(model: BilibiliJsonModel<*>) {
        if (!model.isSuccess) {
            throw BilibiliException.from(model)
        }
    }

    private fun <T> Result<T>.recoverTimeout(message: String): Result<T> =
        recoverCatching { throwable ->
            if (throwable is SocketTimeoutException) {
                throw BilibiliException.from(code = -1, message = message)
            }
            throw throwable
        }.also { result ->
            if (result.isFailure) {
                val t = result.exceptionOrNull()
                if (t is SocketTimeoutException) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "BilibiliRepository",
                        "recoverTimeout",
                        "storageKey=$storageKey",
                    )
                }
            }
        }

    private companion object {
        private const val BASE_API = "https://api.bilibili.com/"
        private const val BASE_PASSPORT = "https://passport.bilibili.com/"
        private const val BASE_COMMENT = "https://comment.bilibili.com/"
    }
}
