package com.xyoye.common_component.bilibili

import com.xyoye.common_component.network.request.RequestParams

/**
 * 将播放偏好转换为 Bilibili playurl 的 Query 参数（不包含 bvid/cid/wts/w_rid）。
 *
 * 说明：
 * - AUTO：主请求优先 DASH；如失败可用 [fallbackParamsOrNull] 进行 MP4 回退重试。
 * - 4K：通常需要同时满足 allow4k=1、fourk=1、qn=120 且账号具备权限，实际以服务端返回为准。
 */
object BilibiliPlayurlPreferencesMapper {
    private const val FNVAL_MP4 = 1
    private const val FNVAL_DASH = 16
    private const val FNVAL_ALLOW_4K = 128
    private const val FNVAL_DASH_ALL = 4048

    fun archivePrimaryParams(
        preferences: BilibiliPlaybackPreferences,
        apiType: BilibiliApiType
    ): RequestParams =
        when (apiType) {
            BilibiliApiType.WEB ->
                when (preferences.playMode) {
                    BilibiliPlayMode.MP4 -> buildMp4Params(preferences, platform = "pc")
                    BilibiliPlayMode.DASH,
                    BilibiliPlayMode.AUTO -> buildDashParams(preferences, platform = "pc", includeCodecid = true)
                }

            BilibiliApiType.TV ->
                when (preferences.playMode) {
                    BilibiliPlayMode.MP4 -> buildMp4Params(preferences, platform = "android")
                    BilibiliPlayMode.DASH,
                    BilibiliPlayMode.AUTO ->
                        buildDashParams(
                            preferences,
                            platform = "android",
                            includeCodecid = true,
                            fnvalOverride = FNVAL_DASH_ALL,
                        )
                }
        }

    fun archiveFallbackParamsOrNull(
        preferences: BilibiliPlaybackPreferences,
        apiType: BilibiliApiType
    ): RequestParams? =
        if (preferences.playMode != BilibiliPlayMode.AUTO) {
            null
        } else {
            when (apiType) {
                BilibiliApiType.WEB -> buildMp4Params(preferences, platform = "pc")
                BilibiliApiType.TV -> buildMp4Params(preferences, platform = "android")
            }
        }

    fun pgcPrimaryParams(
        preferences: BilibiliPlaybackPreferences,
        apiType: BilibiliApiType
    ): RequestParams =
        when (apiType) {
            BilibiliApiType.WEB ->
                when (preferences.playMode) {
                    BilibiliPlayMode.MP4 -> buildMp4Params(preferences, platform = null)
                    BilibiliPlayMode.DASH,
                    BilibiliPlayMode.AUTO ->
                        buildDashParams(
                            preferences,
                            platform = null,
                            includeCodecid = false,
                            fnvalOverride = FNVAL_DASH_ALL,
                        )
                }

            BilibiliApiType.TV ->
                when (preferences.playMode) {
                    BilibiliPlayMode.MP4 -> buildMp4Params(preferences, platform = "android")
                    BilibiliPlayMode.DASH,
                    BilibiliPlayMode.AUTO ->
                        buildDashParams(
                            preferences,
                            platform = "android",
                            includeCodecid = false,
                            fnvalOverride = FNVAL_DASH_ALL,
                        )
                }
        }

    fun pgcFallbackParamsOrNull(
        preferences: BilibiliPlaybackPreferences,
        apiType: BilibiliApiType
    ): RequestParams? =
        if (preferences.playMode != BilibiliPlayMode.AUTO) {
            null
        } else {
            when (apiType) {
                BilibiliApiType.WEB -> buildMp4Params(preferences, platform = null)
                BilibiliApiType.TV -> buildMp4Params(preferences, platform = "android")
            }
        }

    fun primaryParams(
        preferences: BilibiliPlaybackPreferences,
        apiType: BilibiliApiType
    ): RequestParams = archivePrimaryParams(preferences, apiType)

    fun fallbackParamsOrNull(
        preferences: BilibiliPlaybackPreferences,
        apiType: BilibiliApiType
    ): RequestParams? = archiveFallbackParamsOrNull(preferences, apiType)

    private fun buildDashParams(
        preferences: BilibiliPlaybackPreferences,
        platform: String?,
        includeCodecid: Boolean,
        fnvalOverride: Int? = null
    ): RequestParams {
        val params: RequestParams = hashMapOf()
        params["fnver"] = 0
        platform?.let { params["platform"] = it }
        params["fnval"] = fnvalOverride ?: dashFnval(preferences)

        if (preferences.allow4k) {
            params["fourk"] = 1
        }

        if (preferences.preferredQualityQn != BilibiliQuality.AUTO.qn) {
            params["qn"] = preferences.preferredQualityQn
        }

        if (includeCodecid) {
            preferences.preferredVideoCodec.codecid?.let {
                params["codecid"] = it
            }
        }

        return params
    }

    private fun buildMp4Params(
        preferences: BilibiliPlaybackPreferences,
        platform: String?
    ): RequestParams {
        val params: RequestParams = hashMapOf()
        params["fnver"] = 0
        platform?.let { params["platform"] = it }
        params["fnval"] = FNVAL_MP4

        if (preferences.allow4k) {
            params["fourk"] = 1
        }

        if (preferences.preferredQualityQn != BilibiliQuality.AUTO.qn) {
            params["qn"] = preferences.preferredQualityQn
        }

        return params
    }

    private fun dashFnval(preferences: BilibiliPlaybackPreferences): Int {
        var fnval = FNVAL_DASH
        if (preferences.allow4k) {
            fnval = fnval or FNVAL_ALLOW_4K
        }
        return fnval
    }
}
