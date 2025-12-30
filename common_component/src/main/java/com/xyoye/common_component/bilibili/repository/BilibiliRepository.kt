package com.xyoye.common_component.bilibili.repository

import android.util.Base64
import com.xyoye.common_component.bilibili.BilibiliPlayurlPreferencesMapper
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferences
import com.xyoye.common_component.bilibili.BilibiliApiPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliApiType
import com.xyoye.common_component.bilibili.app.BilibiliAppSigner
import com.xyoye.common_component.bilibili.app.BilibiliTvClient
import com.xyoye.common_component.bilibili.auth.BilibiliAuthStore
import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.history.BilibiliHistoryCacheStore
import com.xyoye.common_component.bilibili.login.BilibiliLoginQrCode
import com.xyoye.common_component.bilibili.login.BilibiliLoginPollResult
import com.xyoye.common_component.bilibili.risk.BilibiliGaiaActivateRequest
import com.xyoye.common_component.bilibili.risk.BilibiliRiskStateStore
import com.xyoye.common_component.bilibili.ticket.BilibiliTicketSigner
import com.xyoye.common_component.bilibili.wbi.BilibiliWbiSigner
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.network.request.RequestParams
import com.xyoye.common_component.network.repository.BaseRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.data.bilibili.BilibiliCookieInfoData
import com.xyoye.data_component.data.bilibili.BilibiliCookieRefreshData
import com.xyoye.data_component.data.bilibili.BilibiliGaiaVgateRegisterData
import com.xyoye.data_component.data.bilibili.BilibiliGaiaVgateValidateData
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData
import com.xyoye.data_component.data.bilibili.BilibiliJsonModel
import com.xyoye.data_component.data.bilibili.BilibiliLiveDanmuConnectInfo
import com.xyoye.data_component.data.bilibili.BilibiliLiveDanmuInfoData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlData
import com.xyoye.data_component.data.bilibili.BilibiliLiveRoomInfoData
import com.xyoye.data_component.data.bilibili.BilibiliNavData
import com.xyoye.data_component.data.bilibili.BilibiliPagelistItem
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodeGenerateData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodePollData
import com.xyoye.data_component.data.bilibili.BilibiliResultJsonModel
import com.xyoye.data_component.data.bilibili.BilibiliTvCookieInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.ResponseBody
import java.util.UUID
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class BilibiliRepository(
    private val storageKey: String,
) : BaseRepository() {
    private val service by lazy { Retrofit.bilibiliService(storageKey) }
    private val cookieJarStore by lazy { BilibiliCookieJarStore(storageKey) }
    private val historyCacheStore by lazy { BilibiliHistoryCacheStore(storageKey) }
    private val riskStateStore by lazy { BilibiliRiskStateStore(storageKey) }

    private val cookieRefreshMutex = Mutex()
    private var lastCookieInfoCheckAt: Long = 0L
    private var lastCookieRefreshAttemptAt: Long = 0L

    private val biliTicketMutex = Mutex()
    private var lastBiliTicketAttemptAt: Long = 0L

    private val preheatMutex = Mutex()
    private var lastPreheatAttemptAt: Long = 0L

    private val gaiaActivateMutex = Mutex()
    private var lastGaiaActivateAttemptAt: Long = 0L

    private val historyFirstPageMemoryCache: MutableMap<String, BilibiliHistoryCursorData> = hashMapOf()
    private val historyFirstPageMemoryAt: MutableMap<String, Long> = hashMapOf()

    fun isLoggedIn(): Boolean = cookieJarStore.isLoginCookiePresent()

    fun cookieHeaderOrNull(): String? = cookieJarStore.exportCookieHeader()

    suspend fun gaiaVgateRegister(vVoucher: String): Result<BilibiliGaiaVgateRegisterData> {
        val params: RequestParams = hashMapOf()
        params["v_voucher"] = vVoucher

        BilibiliAuthStore.read(storageKey).csrf?.takeIf { it.isNotBlank() }?.let {
            params["csrf"] = it
        }

        return requestBilibiliAuthed(reason = "gaiaVgateRegister") {
            service.gaiaVgateRegister(BASE_API, params)
        }
    }

    suspend fun gaiaVgateValidate(
        challenge: String,
        token: String,
        validate: String,
        seccode: String,
    ): Result<String> =
        requestBilibiliAuthed(reason = "gaiaVgateValidate") {
            val params: RequestParams = hashMapOf()
            params["challenge"] = challenge
            params["token"] = token
            params["validate"] = validate
            params["seccode"] = seccode

            BilibiliAuthStore.read(storageKey).csrf?.takeIf { it.isNotBlank() }?.let {
                params["csrf"] = it
            }

            service.gaiaVgateValidate(BASE_API, params)
        }.mapCatching { data: BilibiliGaiaVgateValidateData ->
            if (data.isValid != 1) {
                throw BilibiliException.from(code = -352, message = "验证码校验失败，请重试")
            }

            val griskId =
                data.griskId?.takeIf { it.isNotBlank() }
                    ?: throw BilibiliException.from(code = -352, message = "验证码校验失败：grisk_id 为空")

            cookieJarStore.upsertCookie(
                Cookie
                    .Builder()
                    .name("x-bili-gaia-vtoken")
                    .value(griskId)
                    .expiresAt(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7))
                    .domain("bilibili.com")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build(),
            )

            griskId
        }

    private fun isPreheatCookieReady(): Boolean =
        cookieJarStore.isBuvid3CookiePresent() &&
            cookieJarStore.isCookiePresent(COOKIE_BUVID4) &&
            cookieJarStore.isCookiePresent(COOKIE_B_NUT)

    /**
     * 预热 www 域名以补齐基础 Cookie（buvid3/buvid4/b_nut...），降低 -412 风控概率。
     *
     * 注意：预热失败不应阻塞主流程，但应尽可能少地频繁重试。
     */
    private suspend fun preheatIfNeeded(
        force: Boolean,
        reason: String,
    ): Result<Boolean> =
        preheatMutex.withLock {
            runCatching {
                val now = System.currentTimeMillis()
                val lastPreheatAt = riskStateStore.lastPreheatAt()
                val need =
                    force ||
                        !isPreheatCookieReady() ||
                        now - lastPreheatAt >= PREHEAT_TTL_MS

                if (!need) {
                    return@runCatching false
                }

                if (!force && now - lastPreheatAttemptAt < PREHEAT_MIN_RETRY_INTERVAL_MS) {
                    return@runCatching false
                }
                lastPreheatAttemptAt = now

                service.preheat(BASE_WWW).close()
                riskStateStore.updatePreheatAt(now)
                true
            }
        }.onFailure { t ->
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "BilibiliRepository",
                "preheatIfNeeded",
                "storageKey=$storageKey reason=$reason force=$force",
            )
        }

    /**
     * GAIA 风控网关激活（对齐 PiliPlus 的 buvidActive），用于降低高风控接口（如 playurl）命中概率。
     *
     * 激活失败不阻塞主流程；使用 TTL + 最小重试间隔避免频繁触发。
     */
    private suspend fun activateBuvidIfNeeded(
        force: Boolean,
        reason: String,
    ): Result<Boolean> =
        gaiaActivateMutex.withLock {
            runCatching {
                val now = System.currentTimeMillis()
                val lastActivatedAt = riskStateStore.lastGaiaActivateAt()
                val need = force || now - lastActivatedAt >= GAIA_ACTIVATE_TTL_MS

                if (!need) {
                    return@runCatching false
                }

                if (!force && now - lastGaiaActivateAttemptAt < GAIA_ACTIVATE_MIN_RETRY_INTERVAL_MS) {
                    return@runCatching false
                }
                lastGaiaActivateAttemptAt = now

                val payload = buildGaiaActivatePayload()
                service.gaiaActivateBuvid(BASE_API, BilibiliGaiaActivateRequest(payload)).close()
                riskStateStore.updateGaiaActivateAt(now)
                true
            }
        }.onFailure { t ->
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "BilibiliRepository",
                "activateBuvidIfNeeded",
                "storageKey=$storageKey reason=$reason force=$force",
            )
        }

    private fun buildGaiaActivatePayload(): String {
        val randomTailBytes = ByteArray(32 + 8 + 4)
        for (i in 0 until 32) {
            randomTailBytes[i] = Random.nextInt(0, 256).toByte()
        }
        // 0x00000000 + 'IEND'（模拟 PNG 结尾片段）
        randomTailBytes[32] = 0
        randomTailBytes[33] = 0
        randomTailBytes[34] = 0
        randomTailBytes[35] = 0
        randomTailBytes[36] = 73
        randomTailBytes[37] = 69
        randomTailBytes[38] = 78
        randomTailBytes[39] = 68
        for (i in 0 until 4) {
            randomTailBytes[40 + i] = Random.nextInt(0, 256).toByte()
        }

        val bfe9 =
            Base64.encodeToString(randomTailBytes, Base64.NO_WRAP)
                .takeLast(50)

        // payload 字段本身是一个 JSON 字符串（与 PiliPlus 一致）
        return "{\"3064\":1,\"39c8\":\"$GAIA_SPM_RISK\",\"3c43\":{\"adca\":\"Android\",\"bfe9\":\"$bfe9\"}}"
    }

    private fun buildPlayurlSessionOrNull(): String? {
        val buvid3 = cookieJarStore.getCookieOrNull(COOKIE_BUVID3)?.value?.takeIf { it.isNotBlank() } ?: return null
        val ts = System.currentTimeMillis().toString()
        return (buvid3 + ts).toMd5String().orEmpty().takeIf { it.isNotBlank() }
    }

    private suspend fun prepareRiskControl(
        reason: String,
        force: Boolean,
    ) {
        preheatIfNeeded(force = force, reason = reason).getOrNull()
        activateBuvidIfNeeded(force = force, reason = reason).getOrNull()
    }

    private fun applyWebPlayurlRiskParams(
        params: MutableMap<String, Any?>,
        allowTryLook: Boolean,
    ) {
        params["gaia_source"] = PLAYURL_GAIA_SOURCE
        params["isGaiaAvoided"] = true
        params["web_location"] = PLAYURL_WEB_LOCATION

        val fnval = (params["fnval"] as? Number)?.toInt()
        // session 在部分场景可辅助取流，但对 MP4/HTML5 通常不是必需参数；避免传入不一致导致异常。
        if (fnval != null && fnval != 1) {
            buildPlayurlSessionOrNull()?.let { params["session"] = it }
        }

        // 若已存在 GAIA vtoken（Cookie），同时追加到 URL 参数以恢复部分被风控接口的正常访问。
        cookieJarStore.getCookieOrNull(COOKIE_X_BILI_GAIA_VTOKEN)?.value?.takeIf { it.isNotBlank() }?.let {
            params["gaia_vtoken"] = it
        }
        if (allowTryLook) {
            params["try_look"] = 1
        }
    }

    private fun hasPlayableStream(data: BilibiliPlayurlData): Boolean {
        val dashVideoOk = data.dash?.video?.isNotEmpty() == true
        val durlOk = data.durl.any { it.url.isNotBlank() }
        return dashVideoOk || durlOk
    }

    private suspend fun <T> retryBilibiliRiskControlWithRemedy(
        reason: String,
        maxAttempts: Int,
        initialDelayMs: Long,
        maxDelayMs: Long,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var remedied = false
        return retryBilibiliRiskControl(
            maxAttempts = maxAttempts,
            initialDelayMs = initialDelayMs,
            maxDelayMs = maxDelayMs,
        ) {
            val result = block()
            val error = result.exceptionOrNull() as? BilibiliException
            if (!remedied && error != null && isRiskControlError(error)) {
                remedied = true
                prepareRiskControl(reason = "$reason(risk)", force = true)
            }
            result
        }
    }

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
                    webRefreshToken = data.refreshToken,
                )
            }
        }

    suspend fun loginQrCodeGenerate(): Result<BilibiliLoginQrCode> =
        when (currentApiType()) {
            BilibiliApiType.WEB -> {
                qrcodeGenerate().mapCatching { data ->
                    if (data.url.isBlank() || data.qrcodeKey.isBlank()) {
                        throw BilibiliException.from(-1, "获取二维码失败")
                    }
                    BilibiliLoginQrCode(
                        url = data.url,
                        qrcodeKey = data.qrcodeKey,
                    )
                }
            }

            BilibiliApiType.TV -> {
                val params: RequestParams =
                    hashMapOf(
                        "local_id" to BilibiliTvClient.LOCAL_ID,
                    )
                val signed =
                    BilibiliAppSigner.sign(
                        params = params,
                        appKey = BilibiliTvClient.APP_KEY,
                        appSec = BilibiliTvClient.APP_SEC,
                    )
                requestBilibili {
                    service.tvQrcodeAuthCode(BASE_PASSPORT, signed)
                }.mapCatching { data ->
                    if (data.url.isBlank() || data.authCode.isBlank()) {
                        throw BilibiliException.from(-1, "获取二维码失败")
                    }
                    BilibiliLoginQrCode(
                        url = data.url,
                        qrcodeKey = data.authCode,
                    )
                }
            }
        }

    suspend fun loginQrCodePoll(qrcodeKey: String): Result<BilibiliLoginPollResult> =
        when (currentApiType()) {
            BilibiliApiType.WEB -> {
                qrcodePoll(qrcodeKey).mapCatching { data ->
                    when (data.statusCode) {
                        86101 -> BilibiliLoginPollResult.WaitingScan
                        86090 -> BilibiliLoginPollResult.WaitingConfirm
                        86038 -> BilibiliLoginPollResult.Expired
                        0 -> BilibiliLoginPollResult.Success
                        else -> BilibiliLoginPollResult.Error(data.statusMessage ?: "登录中…")
                    }
                }
            }

            BilibiliApiType.TV -> {
                val params: RequestParams =
                    hashMapOf(
                        "auth_code" to qrcodeKey,
                        "local_id" to BilibiliTvClient.LOCAL_ID,
                    )
                val signed =
                    BilibiliAppSigner.sign(
                        params = params,
                        appKey = BilibiliTvClient.APP_KEY,
                        appSec = BilibiliTvClient.APP_SEC,
                    )

                request()
                    .doGet {
                        service.tvQrcodePoll(BASE_PASSPORT, signed)
                    }.mapCatching { model ->
                        when (model.code) {
                            0 -> {
                                val data = model.data ?: throw BilibiliException.from(-1, "登录成功但未返回数据")
                                // 保存 access_token/refresh_token（用于 TV/API 鉴权）
                                BilibiliAuthStore.updateAppTokens(
                                    storageKey = storageKey,
                                    accessToken = data.accessToken,
                                    refreshToken = data.refreshToken,
                                )
                                // 写入 cookie_info.cookes（部分接口仍依赖 Cookie）
                                upsertTvCookiesOrThrow(data.cookieInfo)
                                // 从 Cookie 中提取 csrf/mid 等
                                BilibiliAuthStore.updateFromCookies(
                                    storageKey = storageKey,
                                    cookieJarStore = cookieJarStore,
                                )
                                BilibiliLoginPollResult.Success
                            }

                            86039 -> BilibiliLoginPollResult.WaitingScan
                            86090 -> BilibiliLoginPollResult.WaitingConfirm
                            86038 -> BilibiliLoginPollResult.Expired
                            else -> BilibiliLoginPollResult.Error(model.message.ifBlank { "登录中…" })
                        }
                    }
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
            readCachedHistoryFirstPageOrNull(type)?.let { cached ->
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
                writeCachedHistoryFirstPage(type, data)
            }
        }
    }

    suspend fun liveRoomInfo(roomId: Long): Result<BilibiliLiveRoomInfoData> =
        requestBilibiliAuthed(reason = "liveRoomInfo") {
            service.liveRoomInfo(BASE_LIVE, roomId)
        }

    suspend fun livePlayUrl(
        roomId: Long,
        platform: String = "h5",
    ): Result<BilibiliLivePlayUrlData> {
        val params: RequestParams = hashMapOf()
        params["cid"] = roomId
        params["platform"] = platform

        return requestBilibiliAuthed(reason = "livePlayUrl") {
            service.livePlayUrl(BASE_LIVE, params)
        }.recoverTimeout("取流超时，请检查网络后重试")
    }

    suspend fun liveDanmuInfo(roomId: Long): Result<BilibiliLiveDanmuConnectInfo> {
        val resolvedRoomId =
            liveRoomInfo(roomId)
                .getOrNull()
                ?.roomId
                ?.takeIf { it > 0 }
                ?: roomId

        // B 站近期要求 buvid3/buvid4 等基础 cookie 不为空，预热 www 域名以降低风控概率
        preheatIfNeeded(force = false, reason = "liveDanmuInfo").getOrNull()

        val params: RequestParams =
            hashMapOf(
                "id" to resolvedRoomId,
                "type" to 0,
                "web_location" to "444.8",
            )

        val signed =
            BilibiliWbiSigner.sign(params) {
                fetchWbiKeys()
            }

        return requestBilibiliAuthed(reason = "liveDanmuInfo") {
            service.liveDanmuInfo(BASE_LIVE, signed)
        }.map { data ->
            BilibiliLiveDanmuConnectInfo(
                roomId = resolvedRoomId,
                token = data.token,
                hostList = data.hostList,
            )
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
        val apiType = currentApiType()
        val baseParams = hashMapOf<String, Any?>()
        baseParams["bvid"] = bvid
        baseParams["cid"] = cid
        baseParams.putAll(BilibiliPlayurlPreferencesMapper.primaryParams(preferences, apiType))

        return when (apiType) {
            BilibiliApiType.WEB -> run {
                val allowTryLook = !isLoggedIn()
                prepareRiskControl(reason = "playurl", force = false)

                val pcResult =
                    retryBilibiliRiskControlWithRemedy(
                        reason = "playurl",
                        maxAttempts = PLAYURL_RISK_MAX_ATTEMPTS,
                        initialDelayMs = PLAYURL_RISK_INITIAL_DELAY_MS,
                        maxDelayMs = PLAYURL_RISK_MAX_DELAY_MS,
                    ) {
                        val attemptParams = baseParams.toMutableMap()
                        applyWebPlayurlRiskParams(attemptParams, allowTryLook)
                        val signed =
                            BilibiliWbiSigner.sign(attemptParams) {
                                fetchWbiKeys()
                            }

                        requestBilibiliAuthed(reason = "playurl") {
                            service.playurl(BASE_API, signed)
                        }.recoverTimeout("取流超时，请检查网络后重试").mapCatching { data ->
                            if (hasPlayableStream(data)) {
                                data
                            } else if (!data.vVoucher.isNullOrBlank()) {
                                throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=${data.vVoucher}）")
                            } else {
                                throw BilibiliException.from(code = -1, message = "取流失败：响应无可用流")
                            }
                        }
                    }
                if (pcResult.isSuccess) {
                    return@run pcResult
                }

                val pcError = pcResult.exceptionOrNull() as? BilibiliException
                val fnval = (baseParams["fnval"] as? Number)?.toInt()
                val shouldTryHtml5 = fnval == 1 && pcError?.code in RISK_CONTROL_CODES

                val html5Result =
                    if (shouldTryHtml5) {
                        retryBilibiliRiskControlWithRemedy(
                            reason = "playurl(html5)",
                            maxAttempts = PLAYURL_RISK_MAX_ATTEMPTS,
                            initialDelayMs = PLAYURL_RISK_INITIAL_DELAY_MS,
                            maxDelayMs = PLAYURL_RISK_MAX_DELAY_MS,
                        ) {
                            val attemptParams = baseParams.toMutableMap()
                            attemptParams["platform"] = "html5"
                            attemptParams["high_quality"] = 1
                            applyWebPlayurlRiskParams(attemptParams, allowTryLook)
                            val signed =
                                BilibiliWbiSigner.sign(attemptParams) {
                                    fetchWbiKeys()
                                }

                            requestBilibiliAuthed(reason = "playurl(html5)") {
                                service.playurl(BASE_API, signed)
                            }.recoverTimeout("取流超时，请检查网络后重试").mapCatching { data ->
                                if (hasPlayableStream(data)) {
                                    data
                                } else if (!data.vVoucher.isNullOrBlank()) {
                                    throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=${data.vVoucher}）")
                                } else {
                                    throw BilibiliException.from(code = -1, message = "取流失败：响应无可用流")
                                }
                            }
                        }
                    } else {
                        null
                    }
                if (html5Result?.isSuccess == true) {
                    return@run html5Result
                }

                // Web 无法取流时，尝试使用 TV/API 签名链路作为兜底（不改变用户偏好）。
                val tvResult =
                    retryBilibiliRiskControlWithRemedy(
                        reason = "playurl(tvFallback)",
                        maxAttempts = PLAYURL_RISK_MAX_ATTEMPTS,
                        initialDelayMs = PLAYURL_RISK_INITIAL_DELAY_MS,
                        maxDelayMs = PLAYURL_RISK_MAX_DELAY_MS,
                    ) {
                        val attemptParams = baseParams.toMutableMap()
                        val auth = BilibiliAuthStore.read(storageKey)
                        auth.appAccessToken?.takeIf { it.isNotBlank() }?.let { attemptParams["access_key"] = it }
                        attemptParams["mobi_app"] = BilibiliTvClient.MOBI_APP
                        attemptParams["platform"] = BilibiliTvClient.PLATFORM

                        val signed =
                            BilibiliAppSigner.sign(
                                params = attemptParams,
                                appKey = BilibiliTvClient.APP_KEY,
                                appSec = BilibiliTvClient.APP_SEC,
                            )

                        requestBilibili {
                            service.playurlOld(BASE_API, signed)
                        }.recoverTimeout("取流超时，请检查网络后重试").mapCatching { data ->
                            if (hasPlayableStream(data)) {
                                data
                            } else if (!data.vVoucher.isNullOrBlank()) {
                                throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=${data.vVoucher}）")
                            } else {
                                throw BilibiliException.from(code = -1, message = "取流失败：响应无可用流")
                            }
                        }
                    }
                if (tvResult.isSuccess) {
                    tvResult
                } else {
                    html5Result ?: pcResult
                }
            }

            BilibiliApiType.TV -> {
                prepareRiskControl(reason = "playurl(tv)", force = false)

                retryBilibiliRiskControlWithRemedy(
                    reason = "playurl(tv)",
                    maxAttempts = PLAYURL_RISK_MAX_ATTEMPTS,
                    initialDelayMs = PLAYURL_RISK_INITIAL_DELAY_MS,
                    maxDelayMs = PLAYURL_RISK_MAX_DELAY_MS,
                ) {
                    val attemptParams = baseParams.toMutableMap()
                    val auth = BilibiliAuthStore.read(storageKey)
                    auth.appAccessToken?.takeIf { it.isNotBlank() }?.let { attemptParams["access_key"] = it }
                    attemptParams["mobi_app"] = BilibiliTvClient.MOBI_APP
                    attemptParams["platform"] = BilibiliTvClient.PLATFORM

                    val signed =
                        BilibiliAppSigner.sign(
                            params = attemptParams,
                            appKey = BilibiliTvClient.APP_KEY,
                            appSec = BilibiliTvClient.APP_SEC,
                        )

                    requestBilibili {
                        service.playurlOld(BASE_API, signed)
                    }.recoverTimeout("取流超时，请检查网络后重试").mapCatching { data ->
                        if (hasPlayableStream(data)) {
                            data
                        } else if (!data.vVoucher.isNullOrBlank()) {
                            throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=${data.vVoucher}）")
                        } else {
                            throw BilibiliException.from(code = -1, message = "取流失败：响应无可用流")
                        }
                    }
                }
            }
        }
    }

    suspend fun playurlFallbackOrNull(
        bvid: String,
        cid: Long,
        preferences: BilibiliPlaybackPreferences,
    ): Result<BilibiliPlayurlData>? {
        val apiType = currentApiType()
        val fallback = BilibiliPlayurlPreferencesMapper.fallbackParamsOrNull(preferences, apiType) ?: return null

        val baseParams = hashMapOf<String, Any?>()
        baseParams["bvid"] = bvid
        baseParams["cid"] = cid
        baseParams.putAll(fallback)

        return when (apiType) {
            BilibiliApiType.WEB -> {
                val allowTryLook = !isLoggedIn()
                prepareRiskControl(reason = "playurlFallback", force = false)

                retryBilibiliRiskControlWithRemedy(
                    reason = "playurlFallback",
                    maxAttempts = PLAYURL_RISK_MAX_ATTEMPTS,
                    initialDelayMs = PLAYURL_RISK_INITIAL_DELAY_MS,
                    maxDelayMs = PLAYURL_RISK_MAX_DELAY_MS,
                ) {
                    val attemptParams = baseParams.toMutableMap()
                    applyWebPlayurlRiskParams(attemptParams, allowTryLook)
                    val signed =
                        BilibiliWbiSigner.sign(attemptParams) {
                            fetchWbiKeys()
                        }
                    requestBilibiliAuthed(reason = "playurlFallback") {
                        service.playurl(BASE_API, signed)
                    }.recoverTimeout("取流超时，请检查网络后重试").mapCatching { data ->
                        if (hasPlayableStream(data)) {
                            data
                        } else if (!data.vVoucher.isNullOrBlank()) {
                            throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=${data.vVoucher}）")
                        } else {
                            throw BilibiliException.from(code = -1, message = "取流失败：响应无可用流")
                        }
                    }
                }
            }

            BilibiliApiType.TV -> {
                prepareRiskControl(reason = "playurlFallback(tv)", force = false)

                retryBilibiliRiskControlWithRemedy(
                    reason = "playurlFallback(tv)",
                    maxAttempts = PLAYURL_RISK_MAX_ATTEMPTS,
                    initialDelayMs = PLAYURL_RISK_INITIAL_DELAY_MS,
                    maxDelayMs = PLAYURL_RISK_MAX_DELAY_MS,
                ) {
                    val attemptParams = baseParams.toMutableMap()
                    val auth = BilibiliAuthStore.read(storageKey)
                    auth.appAccessToken?.takeIf { it.isNotBlank() }?.let { attemptParams["access_key"] = it }
                    attemptParams["mobi_app"] = BilibiliTvClient.MOBI_APP
                    attemptParams["platform"] = BilibiliTvClient.PLATFORM

                    val signed =
                        BilibiliAppSigner.sign(
                            params = attemptParams,
                            appKey = BilibiliTvClient.APP_KEY,
                            appSec = BilibiliTvClient.APP_SEC,
                        )

                    requestBilibili {
                        service.playurlOld(BASE_API, signed)
                    }.recoverTimeout("取流超时，请检查网络后重试").mapCatching { data ->
                        if (hasPlayableStream(data)) {
                            data
                        } else if (!data.vVoucher.isNullOrBlank()) {
                            throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=${data.vVoucher}）")
                        } else {
                            throw BilibiliException.from(code = -1, message = "取流失败：响应无可用流")
                        }
                    }
                }
            }
        }
    }

    suspend fun pgcPlayurl(
        epId: Long,
        cid: Long,
        avid: Long? = null,
        preferences: BilibiliPlaybackPreferences,
        session: String? = null,
    ): Result<BilibiliPlayurlData> {
        val params: RequestParams = hashMapOf()
        params["ep_id"] = epId
        params["cid"] = cid
        avid?.takeIf { it > 0 }?.let {
            params["avid"] = it
        }

        params["session"] = session?.takeIf { it.isNotBlank() } ?: newPgcSession()
        params["from_client"] = PGC_FROM_CLIENT
        params["drm_tech_type"] = PGC_DRM_TECH_TYPE

        val apiType = currentApiType()
        params.putAll(BilibiliPlayurlPreferencesMapper.pgcPrimaryParams(preferences, apiType))

        return when (apiType) {
            BilibiliApiType.WEB ->
                prepareRiskControl(reason = "pgcPlayurl", force = false).let {
                    retryBilibiliRiskControlWithRemedy(
                        reason = "pgcPlayurl",
                        maxAttempts = PGC_PLAYURL_RISK_MAX_ATTEMPTS,
                        initialDelayMs = PGC_PLAYURL_RISK_INITIAL_DELAY_MS,
                        maxDelayMs = PGC_PLAYURL_RISK_MAX_DELAY_MS,
                    ) {
                    requestBilibiliResultAuthed(reason = "pgcPlayurl") {
                        service.pgcPlayurl(BASE_API, params)
                    }.recoverTimeout("取流超时，请检查网络后重试")
                }
                }

            BilibiliApiType.TV -> {
                prepareRiskControl(reason = "pgcPlayurl(tv)", force = false)

                val baseTvParams = params.toMutableMap<String, Any?>()
                // TV/API 接口不依赖 web session/from_client/drm_tech_type
                baseTvParams.remove("session")
                baseTvParams.remove("from_client")
                baseTvParams.remove("drm_tech_type")

                val auth = BilibiliAuthStore.read(storageKey)
                auth.appAccessToken?.takeIf { it.isNotBlank() }?.let { baseTvParams["access_key"] = it }
                baseTvParams["mobi_app"] = BilibiliTvClient.MOBI_APP
                baseTvParams["platform"] = BilibiliTvClient.PLATFORM

                retryBilibiliRiskControlWithRemedy(
                    reason = "pgcPlayurl(tv)",
                    maxAttempts = PGC_PLAYURL_RISK_MAX_ATTEMPTS,
                    initialDelayMs = PGC_PLAYURL_RISK_INITIAL_DELAY_MS,
                    maxDelayMs = PGC_PLAYURL_RISK_MAX_DELAY_MS,
                ) {
                    val signed =
                        BilibiliAppSigner.sign(
                            params = baseTvParams,
                            appKey = BilibiliTvClient.APP_KEY,
                            appSec = BilibiliTvClient.APP_SEC,
                        )
                    request()
                        .doGet { service.pgcPlayurlApi(BASE_API, signed) }
                        .mapCatching { model ->
                            if (model.code != 0) {
                                throw BilibiliException.from(code = model.code, message = model.message)
                            }
                            BilibiliPlayurlData(
                                dash = model.dash,
                                durl = model.durl,
                            )
                        }.recoverTimeout("取流超时，请检查网络后重试")
                }
            }
        }
    }

    suspend fun pgcPlayurlFallbackOrNull(
        epId: Long,
        cid: Long,
        avid: Long? = null,
        preferences: BilibiliPlaybackPreferences,
        session: String? = null,
    ): Result<BilibiliPlayurlData>? {
        val apiType = currentApiType()
        val fallback = BilibiliPlayurlPreferencesMapper.pgcFallbackParamsOrNull(preferences, apiType) ?: return null
        val params: RequestParams = hashMapOf()
        params["ep_id"] = epId
        params["cid"] = cid
        avid?.takeIf { it > 0 }?.let {
            params["avid"] = it
        }

        params["session"] = session?.takeIf { it.isNotBlank() } ?: newPgcSession()
        params["from_client"] = PGC_FROM_CLIENT
        params["drm_tech_type"] = PGC_DRM_TECH_TYPE

        params.putAll(fallback)

        return when (apiType) {
            BilibiliApiType.WEB ->
                prepareRiskControl(reason = "pgcPlayurlFallback", force = false).let {
                    retryBilibiliRiskControlWithRemedy(
                        reason = "pgcPlayurlFallback",
                        maxAttempts = PGC_PLAYURL_RISK_MAX_ATTEMPTS,
                        initialDelayMs = PGC_PLAYURL_RISK_INITIAL_DELAY_MS,
                        maxDelayMs = PGC_PLAYURL_RISK_MAX_DELAY_MS,
                    ) {
                    requestBilibiliResultAuthed(reason = "pgcPlayurlFallback") {
                        service.pgcPlayurl(BASE_API, params)
                    }.recoverTimeout("取流超时，请检查网络后重试")
                }
                }

            BilibiliApiType.TV -> {
                prepareRiskControl(reason = "pgcPlayurlFallback(tv)", force = false)

                val baseTvParams = params.toMutableMap<String, Any?>()
                baseTvParams.remove("session")
                baseTvParams.remove("from_client")
                baseTvParams.remove("drm_tech_type")

                val auth = BilibiliAuthStore.read(storageKey)
                auth.appAccessToken?.takeIf { it.isNotBlank() }?.let { baseTvParams["access_key"] = it }
                baseTvParams["mobi_app"] = BilibiliTvClient.MOBI_APP
                baseTvParams["platform"] = BilibiliTvClient.PLATFORM

                retryBilibiliRiskControlWithRemedy(
                    reason = "pgcPlayurlFallback(tv)",
                    maxAttempts = PGC_PLAYURL_RISK_MAX_ATTEMPTS,
                    initialDelayMs = PGC_PLAYURL_RISK_INITIAL_DELAY_MS,
                    maxDelayMs = PGC_PLAYURL_RISK_MAX_DELAY_MS,
                ) {
                    val signed =
                        BilibiliAppSigner.sign(
                            params = baseTvParams,
                            appKey = BilibiliTvClient.APP_KEY,
                            appSec = BilibiliTvClient.APP_SEC,
                        )
                    request()
                        .doGet { service.pgcPlayurlApi(BASE_API, signed) }
                        .mapCatching { model ->
                            if (model.code != 0) {
                                throw BilibiliException.from(code = model.code, message = model.message)
                            }
                            BilibiliPlayurlData(
                                dash = model.dash,
                                durl = model.durl,
                            )
                        }.recoverTimeout("取流超时，请检查网络后重试")
                }
            }
        }
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

    private fun currentApiType(): BilibiliApiType =
        BilibiliApiPreferencesStore.read(storageKey).apiType

    private fun normalizeCookieDomain(domain: String): String =
        domain.trim().removePrefix(".").ifBlank { domain }

    private fun upsertTvCookiesOrThrow(cookieInfo: BilibiliTvCookieInfo?) {
        if (cookieInfo == null || cookieInfo.cookies.isEmpty()) return

        val now = System.currentTimeMillis()
        val domains =
            cookieInfo.domains
                .map { normalizeCookieDomain(it) }
                .filter { it.endsWith("bilibili.com", ignoreCase = true) }
                .ifEmpty { listOf("bilibili.com") }

        cookieInfo.cookies.forEach { item ->
            if (item.name.isBlank() || item.value.isBlank()) return@forEach

            val expiresAt =
                item.expires
                    ?.takeIf { it > 0 }
                    ?.let { it * 1000L }
                    ?: (now + 7L * 24L * 60L * 60L * 1000L)

            domains.forEach { domain ->
                val cookie =
                    Cookie
                        .Builder()
                        .name(item.name)
                        .value(item.value)
                        .domain(domain)
                        .path("/")
                        .expiresAt(expiresAt)
                        .apply {
                            if (item.secure == 1) secure()
                            if (item.httpOnly == 1) httpOnly()
                        }.build()
                cookieJarStore.upsertCookie(cookie, bucketHost = domain)
            }
        }
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
        refreshBiliTicketIfNeeded(reason = reason)
        val first = requestBilibili(block)
        val error = first.exceptionOrNull() as? BilibiliException ?: return first
        if (error.code != -101) return first

        val refreshed = refreshCookieIfNeeded(forceCheck = true, reason = "$reason(-101)")
        if (refreshed.isFailure) return first

        return requestBilibili(block)
    }

    private suspend fun <T> requestBilibiliResult(
        block: suspend () -> BilibiliResultJsonModel<T>
    ): Result<T> =
        request()
            .doGet {
                block()
            }.mapCatching { model ->
                model.successData ?: throw BilibiliException.from(0, "响应数据为空")
            }

    private suspend fun <T> requestBilibiliResultAuthed(
        reason: String,
        block: suspend () -> BilibiliResultJsonModel<T>,
    ): Result<T> {
        refreshCookieIfNeeded(forceCheck = false, reason = reason)
        refreshBiliTicketIfNeeded(reason = reason)
        val first = requestBilibiliResult(block)
        val error = first.exceptionOrNull() as? BilibiliException ?: return first
        if (error.code != -101) return first

        val refreshed = refreshCookieIfNeeded(forceCheck = true, reason = "$reason(-101)")
        if (refreshed.isFailure) return first

        return requestBilibiliResult(block)
    }

    private suspend fun refreshBiliTicketIfNeeded(reason: String): Result<Boolean> =
        biliTicketMutex.withLock {
            runCatching {
                val now = System.currentTimeMillis()
                val existing = cookieJarStore.getCookieOrNull(name = COOKIE_BILI_TICKET)
                val needsRefresh =
                    existing == null ||
                        existing.expiresAt <= now + BILI_TICKET_REFRESH_AHEAD_MS

                if (!needsRefresh) {
                    return@runCatching false
                }

                if (now - lastBiliTicketAttemptAt < BILI_TICKET_MIN_RETRY_INTERVAL_MS) {
                    return@runCatching false
                }
                lastBiliTicketAttemptAt = now

                // 预热 www 域名以补齐基础 Cookie，降低风控概率
                preheatIfNeeded(force = false, reason = "biliTicket:$reason").getOrNull()

                val signed = BilibiliTicketSigner.sign()
                val csrf = BilibiliAuthStore.read(storageKey).csrf

                val params: RequestParams = hashMapOf()
                params["key_id"] = signed.keyId
                params["hexsign"] = signed.hexsign
                params["context[ts]"] = signed.timestampSec
                csrf?.takeIf { it.isNotBlank() }?.let {
                    params["csrf"] = it
                }

                val data =
                    requestBilibili {
                        service.genWebTicket(BASE_API, params)
                    }.getOrThrow()

                val ticket = data.ticket.takeIf { it.isNotBlank() }
                    ?: throw BilibiliException.from(code = -1, message = "获取 bili_ticket 失败：响应为空")

                val createdAtSec = data.createdAt.takeIf { it > 0 } ?: signed.timestampSec
                val ttlSec = data.ttl.takeIf { it > 0 } ?: BILI_TICKET_DEFAULT_TTL_SEC
                val expiresAtMs = (createdAtSec + ttlSec) * 1000L

                val cookie =
                    Cookie
                        .Builder()
                        .name(COOKIE_BILI_TICKET)
                        .value(ticket)
                        .domain("bilibili.com")
                        .path("/")
                        .expiresAt(expiresAtMs)
                        .secure()
                        .httpOnly()
                        .build()

                cookieJarStore.upsertCookie(cookie, bucketHost = "bilibili.com")
                true
            }
        }.onFailure { t ->
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "BilibiliRepository",
                "refreshBiliTicketIfNeeded",
                "storageKey=$storageKey reason=$reason",
            )
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
        return readCachedHistoryFirstPageOrNull(type = "archive")
    }

    private fun readCachedHistoryFirstPageOrNull(type: String): BilibiliHistoryCursorData? {
        val now = System.currentTimeMillis()
        val mem = historyFirstPageMemoryCache[type]
        val memAt = historyFirstPageMemoryAt[type] ?: 0L
        if (mem != null && now - memAt <= HISTORY_FIRST_PAGE_CACHE_MAX_AGE_MS) {
            return mem
        }
        val cached = historyCacheStore.readFirstPageOrNull(type, HISTORY_FIRST_PAGE_CACHE_MAX_AGE_MS, now)
        if (cached != null) {
            historyFirstPageMemoryCache[type] = cached
            historyFirstPageMemoryAt[type] = now
        }
        return cached
    }

    private fun writeCachedHistoryFirstPage(type: String, data: BilibiliHistoryCursorData) {
        historyFirstPageMemoryCache[type] = data
        historyFirstPageMemoryAt[type] = System.currentTimeMillis()
        historyCacheStore.writeFirstPage(type, data)
    }

    private suspend fun refreshCookieIfNeeded(
        forceCheck: Boolean,
        reason: String,
    ): Result<Boolean> =
        cookieRefreshMutex.withLock {
            val auth = BilibiliAuthStore.read(storageKey)
            val refreshToken = auth.webRefreshToken?.takeIf { it.isNotBlank() } ?: return@withLock Result.success(false)

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
                webRefreshToken = refreshTokenNew,
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
            if (model.code == -352) {
                val vVoucher = (model.data as? BilibiliPlayurlData)?.vVoucher
                if (!vVoucher.isNullOrBlank()) {
                    throw BilibiliException.from(code = -352, message = "风控校验失败（v_voucher=$vVoucher）")
                }
            }
            throw BilibiliException.from(model)
        }
    }

    private fun newPgcSession(): String = UUID.randomUUID().toString().replace("-", "")

    private fun isRiskControlError(error: BilibiliException): Boolean =
        error.code in RISK_CONTROL_CODES

    private suspend fun <T> retryBilibiliRiskControl(
        maxAttempts: Int,
        initialDelayMs: Long,
        maxDelayMs: Long,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var attempt = 1
        var delayMs = initialDelayMs

        while (true) {
            val result = block()
            val error = result.exceptionOrNull() as? BilibiliException
                ?: return result
            if (!isRiskControlError(error)) {
                return result
            }

            if (attempt >= maxAttempts) {
                return result
            }

            val jitter = Random.nextLong(0, (delayMs / 3).coerceAtLeast(1) + 1)
            delay(delayMs + jitter)
            delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            attempt++
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
        private const val BASE_LIVE = "https://api.live.bilibili.com/"
        private const val BASE_PASSPORT = "https://passport.bilibili.com/"
        private const val BASE_COMMENT = "https://comment.bilibili.com/"
        private const val BASE_WWW = "https://www.bilibili.com/"

        private const val COOKIE_BUVID3 = "buvid3"
        private const val COOKIE_BUVID4 = "buvid4"
        private const val COOKIE_B_NUT = "b_nut"
        private const val COOKIE_X_BILI_GAIA_VTOKEN = "x-bili-gaia-vtoken"

        private const val COOKIE_INFO_CHECK_INTERVAL_MS = 20 * 60 * 60 * 1000L
        private const val COOKIE_REFRESH_MIN_INTERVAL_MS = 2 * 60 * 1000L
        private const val HISTORY_FIRST_PAGE_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        private const val COOKIE_BILI_TICKET = "bili_ticket"
        private const val BILI_TICKET_DEFAULT_TTL_SEC = 259200L
        private const val BILI_TICKET_REFRESH_AHEAD_MS = 12 * 60 * 60 * 1000L
        private const val BILI_TICKET_MIN_RETRY_INTERVAL_MS = 60 * 1000L

        private const val PREHEAT_TTL_MS = 12 * 60 * 60 * 1000L
        private const val PREHEAT_MIN_RETRY_INTERVAL_MS = 60 * 1000L

        private const val GAIA_ACTIVATE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val GAIA_ACTIVATE_MIN_RETRY_INTERVAL_MS = 60 * 1000L
        private const val GAIA_SPM_RISK = "333.1387.fp.risk"

        private const val PLAYURL_GAIA_SOURCE = "pre-load"
        private const val PLAYURL_WEB_LOCATION = 1315873

        private const val PLAYURL_RISK_MAX_ATTEMPTS = 3
        private const val PLAYURL_RISK_INITIAL_DELAY_MS = 400L
        private const val PLAYURL_RISK_MAX_DELAY_MS = 3000L

        private const val PGC_FROM_CLIENT = "BROWSER"
        private const val PGC_DRM_TECH_TYPE = 2

        private const val PGC_PLAYURL_RISK_MAX_ATTEMPTS = 4
        private const val PGC_PLAYURL_RISK_INITIAL_DELAY_MS = 500L
        private const val PGC_PLAYURL_RISK_MAX_DELAY_MS = 4000L

        private val RISK_CONTROL_CODES = setOf(-351, -352, -412, -509)

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
