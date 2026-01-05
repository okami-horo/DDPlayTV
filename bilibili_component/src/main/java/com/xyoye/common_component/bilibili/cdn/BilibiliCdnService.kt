package com.xyoye.common_component.bilibili.cdn

/**
 * CDN 强制策略（可选）：
 * - [AUTO]：不强制 host，使用 playurl 返回的 base/backup 进行排序与回退
 * - 其他项：将 base/backup 的 host 重写到指定节点（并保留原始 URL 作为回退）
 */
enum class BilibiliCdnService(
    val host: String?,
    val label: String,
) {
    AUTO(null, "自动（不强制）"),
    ALI("upos-sz-mirrorali.bilivideo.com", "ali（阿里云）"),
    COS("upos-sz-mirrorcos.bilivideo.com", "cos（腾讯云）"),
    HW("upos-sz-mirrorhw.bilivideo.com", "hw（华为云）"),
    AKAMAI("upos-hz-mirrorakam.akamaized.net", "akamai（海外）"),
}

