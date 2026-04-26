package com.xyoye.common_component.bilibili.repository

import com.xyoye.common_component.bilibili.BilibiliApiType
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferences
import com.xyoye.common_component.bilibili.login.BilibiliLoginPollResult
import com.xyoye.common_component.bilibili.login.BilibiliLoginQrCode
import com.xyoye.data_component.data.bilibili.BilibiliGaiaVgateRegisterData
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData
import com.xyoye.data_component.data.bilibili.BilibiliLiveDanmuConnectInfo
import com.xyoye.data_component.data.bilibili.BilibiliLiveFollowData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlData
import com.xyoye.data_component.data.bilibili.BilibiliLiveRoomInfoData
import com.xyoye.data_component.data.bilibili.BilibiliNavData
import com.xyoye.data_component.data.bilibili.BilibiliPagelistItem
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodeGenerateData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodePollData
import okhttp3.ResponseBody

class BilibiliRepository(
    storageKey: String
) {
    private val core = BilibiliRepositoryCore(storageKey)

    val auth: BilibiliAuthRepository = BilibiliAuthRepository(core)
    val risk: BilibiliRiskRepository = BilibiliRiskRepository(core)
    val history: BilibiliHistoryRepository = BilibiliHistoryRepository(core)
    val live: BilibiliLiveRepository = BilibiliLiveRepository(core)
    val playback: BilibiliPlaybackRepository = BilibiliPlaybackRepository(core)
    val danmaku: BilibiliDanmakuRepository = BilibiliDanmakuRepository(core)

    fun isLoggedIn(): Boolean = auth.isLoggedIn()

    fun cookieHeaderOrNull(): String? = auth.cookieHeaderOrNull()

    suspend fun gaiaVgateRegister(vVoucher: String): Result<BilibiliGaiaVgateRegisterData> = risk.gaiaVgateRegister(vVoucher)

    suspend fun gaiaVgateValidate(
        challenge: String,
        token: String,
        validate: String,
        seccode: String
    ): Result<String> =
        risk.gaiaVgateValidate(
            challenge = challenge,
            token = token,
            validate = validate,
            seccode = seccode,
        )

    suspend fun nav(): Result<BilibiliNavData> = auth.nav()

    suspend fun qrcodeGenerate(): Result<BilibiliQrcodeGenerateData> = auth.qrcodeGenerate()

    suspend fun qrcodePoll(qrcodeKey: String): Result<BilibiliQrcodePollData> = auth.qrcodePoll(qrcodeKey)

    suspend fun loginQrCodeGenerate(apiType: BilibiliApiType = auth.currentApiType()): Result<BilibiliLoginQrCode> =
        auth.loginQrCodeGenerate(apiType)

    suspend fun loginQrCodePoll(
        qrcodeKey: String,
        apiType: BilibiliApiType = auth.currentApiType()
    ): Result<BilibiliLoginPollResult> = auth.loginQrCodePoll(qrcodeKey, apiType)

    suspend fun historyCursor(
        max: Long? = null,
        viewAt: Long? = null,
        business: String? = null,
        ps: Int = 30,
        type: String = "archive",
        preferCache: Boolean = true
    ): Result<BilibiliHistoryCursorData> =
        history.historyCursor(
            max = max,
            viewAt = viewAt,
            business = business,
            ps = ps,
            type = type,
            preferCache = preferCache,
        )

    suspend fun liveFollow(
        page: Int,
        pageSize: Int = 9,
        ignoreRecord: Int = 1,
        hitAb: Boolean = true
    ): Result<BilibiliLiveFollowData> =
        live.liveFollow(
            page = page,
            pageSize = pageSize,
            ignoreRecord = ignoreRecord,
            hitAb = hitAb,
        )

    suspend fun playbackHeartbeat(
        key: BilibiliKeys.Key,
        playedTimeSec: Long
    ): Result<Unit> = playback.playbackHeartbeat(key, playedTimeSec)

    suspend fun liveRoomInfo(roomId: Long): Result<BilibiliLiveRoomInfoData> = live.liveRoomInfo(roomId)

    suspend fun livePlayUrl(
        roomId: Long,
        platform: String = "h5",
        qn: Int? = 10000
    ): Result<BilibiliLivePlayUrlData> = live.livePlayUrl(roomId, platform, qn)

    suspend fun liveDanmuInfo(roomId: Long): Result<BilibiliLiveDanmuConnectInfo> = live.liveDanmuInfo(roomId)

    suspend fun pagelist(bvid: String): Result<List<BilibiliPagelistItem>> = playback.pagelist(bvid)

    suspend fun playurl(
        bvid: String,
        cid: Long,
        preferences: BilibiliPlaybackPreferences
    ): Result<BilibiliPlayurlData> = playback.playurl(bvid, cid, preferences)

    suspend fun playurlFallbackOrNull(
        bvid: String,
        cid: Long,
        preferences: BilibiliPlaybackPreferences
    ): Result<BilibiliPlayurlData>? = playback.playurlFallbackOrNull(bvid, cid, preferences)

    suspend fun pgcPlayurl(
        epId: Long,
        cid: Long,
        avid: Long? = null,
        preferences: BilibiliPlaybackPreferences,
        session: String? = null
    ): Result<BilibiliPlayurlData> =
        playback.pgcPlayurl(
            epId = epId,
            cid = cid,
            avid = avid,
            preferences = preferences,
            session = session,
        )

    suspend fun pgcPlayurlFallbackOrNull(
        epId: Long,
        cid: Long,
        avid: Long? = null,
        preferences: BilibiliPlaybackPreferences,
        session: String? = null
    ): Result<BilibiliPlayurlData>? =
        playback.pgcPlayurlFallbackOrNull(
            epId = epId,
            cid = cid,
            avid = avid,
            preferences = preferences,
            session = session,
        )

    suspend fun danmakuXml(cid: Long): Result<ResponseBody> = danmaku.danmakuXml(cid)

    suspend fun danmakuListSo(cid: Long): Result<ResponseBody> = danmaku.danmakuListSo(cid)

    fun clear() {
        auth.clear()
    }
}
