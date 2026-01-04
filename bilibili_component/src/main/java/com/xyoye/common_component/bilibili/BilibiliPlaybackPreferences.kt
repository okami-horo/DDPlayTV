package com.xyoye.common_component.bilibili

import com.xyoye.common_component.bilibili.cdn.BilibiliCdnService

/**
 * Bilibili 播放偏好配置（用于 playurl 取流与本地 mpd 生成选流）。
 *
 * 注意：部分选项（例如 4K/高码率/HDR 等）可能需要登录或大会员鉴权，实际可用性以服务端返回为准。
 */
data class BilibiliPlaybackPreferences(
    val playMode: BilibiliPlayMode = BilibiliPlayMode.AUTO,
    val preferredQualityQn: Int = BilibiliQuality.QN_720P.qn,
    val preferredVideoCodec: BilibiliVideoCodec = BilibiliVideoCodec.AVC,
    val allow4k: Boolean = false,
    val cdnService: BilibiliCdnService = BilibiliCdnService.AUTO,
)

enum class BilibiliPlayMode(
    val label: String
) {
    /**
     * 自动：优先 DASH，失败回退 MP4（实现阶段落地）。
     */
    AUTO("自动（DASH 优先）"),

    /**
     * 强制使用 DASH。
     */
    DASH("DASH"),

    /**
     * 强制使用 MP4（兼容优先，画质受限）。
     */
    MP4("MP4（兼容优先）"),
}

enum class BilibiliVideoCodec(
    /**
     * 对应 playurl 返回的 dash.video[].codecid
     */
    val codecid: Int?,
    val label: String
) {
    AUTO(null, "自动"),
    AVC(7, "AVC/H.264"),
    HEVC(12, "HEVC/H.265"),
    AV1(13, "AV1"),
}

enum class BilibiliQuality(
    val qn: Int,
    val label: String
) {
    AUTO(0, "自动"),

    QN_360P(16, "360P"),
    QN_480P(32, "480P"),
    QN_720P(64, "720P"),
    QN_1080P(80, "1080P"),
    QN_1080P_PLUS(112, "1080P+"),

    QN_4K(120, "4K"),
    ;

    companion object {
        fun fromQn(qn: Int): BilibiliQuality = entries.firstOrNull { it.qn == qn } ?: AUTO
    }
}
