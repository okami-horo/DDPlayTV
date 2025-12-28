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

    fun primaryParams(preferences: BilibiliPlaybackPreferences): RequestParams =
        when (preferences.playMode) {
            BilibiliPlayMode.MP4 -> mp4Params(preferences)
            BilibiliPlayMode.DASH,
            BilibiliPlayMode.AUTO -> dashParams(preferences)
        }

    fun fallbackParamsOrNull(preferences: BilibiliPlaybackPreferences): RequestParams? =
        if (preferences.playMode == BilibiliPlayMode.AUTO) {
            mp4Params(preferences)
        } else {
            null
        }

    private fun dashParams(preferences: BilibiliPlaybackPreferences): RequestParams {
        val params: RequestParams = hashMapOf()
        params["fnver"] = 0
        params["platform"] = "pc"
        params["fnval"] = dashFnval(preferences)

        if (preferences.allow4k) {
            params["fourk"] = 1
        }

        if (preferences.preferredQualityQn != BilibiliQuality.AUTO.qn) {
            params["qn"] = preferences.preferredQualityQn
        }

        preferences.preferredVideoCodec.codecid?.let {
            params["codecid"] = it
        }

        return params
    }

    private fun mp4Params(preferences: BilibiliPlaybackPreferences): RequestParams {
        val params: RequestParams = hashMapOf()
        params["fnver"] = 0
        params["platform"] = "pc"
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

