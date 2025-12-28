package com.xyoye.common_component.bilibili.repository

import android.util.Base64
import com.xyoye.common_component.bilibili.BilibiliPlayurlPreferencesMapper
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferences
import com.xyoye.common_component.bilibili.auth.BilibiliAuthStore
import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.history.BilibiliHistoryCacheStore
import com.xyoye.common_component.bilibili.wbi.BilibiliWbiSigner
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.request.RequestParams
import com.xyoye.common_component.network.repository.BaseRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.data.bilibili.BilibiliCookieInfoData
import com.xyoye.data_component.data.bilibili.BilibiliCookieRefreshData
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData
import com.xyoye.data_component.data.bilibili.BilibiliJsonModel
import com.xyoye.data_component.data.bilibili.BilibiliNavData
import com.xyoye.data_component.data.bilibili.BilibiliPagelistItem
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodeGenerateData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodePollData
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class BilibiliRepository(
    private val storageKey: String,
) : BaseRepository() {
    private val service by lazy { Retrofit.bilibiliService(storageKey) }
    private val cookieJarStore by lazy { BilibiliCookieJarStore(storageKey) }
    private val historyCacheStore by lazy { BilibiliHistoryCacheStore(storageKey) }

    private val cookieRefreshMutex = Mutex()
    private var lastCookieInfoCheckAt: Long = 0L
    private var lastCookieRefreshAttemptAt: Long = 0L

    private var historyFirstPageMemoryCache: BilibiliHistoryCursorData? = null
    private var historyFirstPageMemoryAt: Long = 0L

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
        preferCache: Boolean = true,
    ): Result<BilibiliHistoryCursorData> {
        val isFirstPage = max == null && viewAt == null && business == null
        if (preferCache && isFirstPage) {
            readCachedHistoryFirstPageOrNull()?.let { cached ->
                // 异步触发“每日检查/需要刷新再刷新”，避免首屏被网络阻塞
                SupervisorScope.IO.launch {
                    refreshCookieIfNeeded(forceCheck = false, reason = "historyCursor(cache)")
                }
                return Result.success(cached)
            }
        }

        val params: RequestParams = hashMapOf()
        max?.let { params["max"] = it }
        viewAt?.let { params["view_at"] = it }
        business?.let { params["business"] = it }
        params["ps"] = ps
        params["type"] = type

        return requestBilibiliAuthed(reason = "historyCursor") {
            service.historyCursor(BASE_API, params)
        }.onSuccess { data ->
            if (isFirstPage) {
                writeCachedHistoryFirstPage(data)
            }
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

        return requestBilibiliAuthed(reason = "playurl") {
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
        return requestBilibiliAuthed(reason = "playurlFallback") {
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
        historyCacheStore.clear()
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

    private suspend fun <T> requestBilibiliAuthed(
        reason: String,
        block: suspend () -> BilibiliJsonModel<T>,
    ): Result<T> {
        refreshCookieIfNeeded(forceCheck = false, reason = reason)
        val first = requestBilibili(block)
        val error = first.exceptionOrNull() as? BilibiliException ?: return first
        if (error.code != -101) return first

        val refreshed = refreshCookieIfNeeded(forceCheck = true, reason = "$reason(-101)")
        if (refreshed.isFailure) return first

        return requestBilibili(block)
    }

    private suspend fun requestBilibiliUnit(
        block: suspend () -> BilibiliJsonModel<Any>,
    ): Result<Unit> =
        request().doGet {
            val model = block()
            ensureSuccess(model)
            Unit
        }

    private fun readCachedHistoryFirstPageOrNull(): BilibiliHistoryCursorData? {
        val now = System.currentTimeMillis()
        if (historyFirstPageMemoryCache != null && now - historyFirstPageMemoryAt <= HISTORY_FIRST_PAGE_CACHE_MAX_AGE_MS) {
            return historyFirstPageMemoryCache
        }
        val cached = historyCacheStore.readFirstPageOrNull(HISTORY_FIRST_PAGE_CACHE_MAX_AGE_MS, now)
        if (cached != null) {
            historyFirstPageMemoryCache = cached
            historyFirstPageMemoryAt = now
        }
        return cached
    }

    private fun writeCachedHistoryFirstPage(data: BilibiliHistoryCursorData) {
        historyFirstPageMemoryCache = data
        historyFirstPageMemoryAt = System.currentTimeMillis()
        historyCacheStore.writeFirstPage(data)
    }

    private suspend fun refreshCookieIfNeeded(
        forceCheck: Boolean,
        reason: String,
    ): Result<Boolean> =
        cookieRefreshMutex.withLock {
            val auth = BilibiliAuthStore.read(storageKey)
            val refreshToken = auth.refreshToken?.takeIf { it.isNotBlank() } ?: return@withLock Result.success(false)

            val now = System.currentTimeMillis()
            if (!forceCheck && now - lastCookieInfoCheckAt < COOKIE_INFO_CHECK_INTERVAL_MS) {
                return@withLock Result.success(false)
            }
            lastCookieInfoCheckAt = now

            val info =
                requestBilibili {
                    service.cookieInfo(BASE_PASSPORT, auth.csrf)
                }.getOrElse { throwable ->
                    val e = throwable as? BilibiliException
                    if (e?.code == -101) {
                        return@withLock Result.failure(BilibiliException.from(code = -101, message = "登录已失效，请重新扫码登录"))
                    }
                    return@withLock Result.success(false)
                }

            if (!info.refresh) {
                return@withLock Result.success(false)
            }

            if (now - lastCookieRefreshAttemptAt < COOKIE_REFRESH_MIN_INTERVAL_MS) {
                return@withLock Result.success(false)
            }
            lastCookieRefreshAttemptAt = now

            val csrf = auth.csrf?.takeIf { it.isNotBlank() }
                ?: return@withLock Result.failure(BilibiliException.from(code = -1, message = "缺少 csrf（bili_jct），请重新扫码登录"))

            val correspondPath =
                runCatching { encryptCorrespondPath(info.timestamp) }
                    .getOrNull()
                    ?: return@withLock Result.failure(BilibiliException.from(code = -1, message = "生成 correspondPath 失败"))

            val refreshCsrf =
                runCatching { service.correspond(BASE_WWW, correspondPath).string() }
                    .mapCatching { extractRefreshCsrf(it) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withLock Result.failure(BilibiliException.from(code = -1, message = "获取 refresh_csrf 失败"))

            val refreshTokenOld = refreshToken
            val refreshData =
                requestBilibili {
                    val params: RequestParams =
                        hashMapOf(
                            "csrf" to csrf,
                            "refresh_csrf" to refreshCsrf,
                            "source" to "main_web",
                            "refresh_token" to refreshTokenOld,
                        )
                    service.cookieRefresh(BASE_PASSPORT, params)
                }.getOrElse { throwable ->
                    val e = throwable as? BilibiliException
                    if (e?.code == -101 || e?.code == 86095) {
                        clear()
                        return@withLock Result.failure(BilibiliException.from(code = -101, message = "登录已失效，请重新扫码登录"))
                    }
                    return@withLock Result.failure(throwable)
                }

            val refreshTokenNew =
                refreshData.refreshToken?.takeIf { it.isNotBlank() }
                    ?: return@withLock Result.failure(BilibiliException.from(code = -1, message = "刷新成功但未返回 refresh_token"))

            // 写入新的 csrf/mid/refresh_token（csrf 从新 cookie 中读取）
            BilibiliAuthStore.updateFromCookies(
                storageKey = storageKey,
                cookieJarStore = cookieJarStore,
                refreshToken = refreshTokenNew,
            )

            val csrfNew =
                BilibiliAuthStore.read(storageKey).csrf?.takeIf { it.isNotBlank() }
                    ?: return@withLock Result.failure(BilibiliException.from(code = -1, message = "刷新 Cookie 后缺少 csrf"))

            // 注意：这里必须使用“刷新前的旧 refresh_token”进行确认
            requestBilibiliUnit {
                val params: RequestParams =
                    hashMapOf(
                        "csrf" to csrfNew,
                        "refresh_token" to refreshTokenOld,
                    )
                service.confirmRefresh(BASE_PASSPORT, params)
            }.getOrElse { throwable ->
                val e = throwable as? BilibiliException
                if (e?.code == -101) {
                    clear()
                    return@withLock Result.failure(BilibiliException.from(code = -101, message = "登录已失效，请重新扫码登录"))
                }
                return@withLock Result.failure(throwable)
            }

            Result.success(true)
        }.onFailure { t ->
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "BilibiliRepository",
                "refreshCookieIfNeeded",
                "storageKey=$storageKey forceCheck=$forceCheck reason=$reason",
            )
        }

    private fun encryptCorrespondPath(timestamp: Long): String {
        val cipher =
            Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    CORRESPOND_PUBLIC_KEY,
                    OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
                )
            }
        val bytes = cipher.doFinal("refresh_$timestamp".toByteArray())
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun extractRefreshCsrf(html: String): String? {
        // 解析 HTML：<div id="1-name">refresh_csrf</div>
        val regex = Regex("<div\\s+id=\"1-name\"[^>]*>([^<]+)</div>")
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifEmpty { null }
    }

    private val CORRESPOND_PUBLIC_KEY: PublicKey by lazy {
        val pem =
            CORRESPOND_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .trim()
        val keyBytes = Base64.decode(pem, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePublic(spec)
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
        private const val BASE_WWW = "https://www.bilibili.com/"

        private const val COOKIE_INFO_CHECK_INTERVAL_MS = 20 * 60 * 60 * 1000L
        private const val COOKIE_REFRESH_MIN_INTERVAL_MS = 2 * 60 * 1000L
        private const val HISTORY_FIRST_PAGE_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        private const val CORRESPOND_PUBLIC_KEY_PEM =
            """
-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
JNrRuoEUXpabUzGB8QIDAQAB
-----END PUBLIC KEY-----
            """
    }
}
