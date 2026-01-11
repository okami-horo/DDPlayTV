package com.xyoye.common_component.bilibili.app

/**
 * TV 客户端（云视听小电视）相关固定参数。
 *
 * 参考：`.tmp/bilibili-API-collect/docs/misc/sign/APPKey.md`
 */
object BilibiliTvClient {
    const val APP_KEY = "4409e2ce8ffd12b8"
    const val APP_SEC = "59b43e04ad6965f34319062b478f83dd"

    const val MOBI_APP = "android_tv_yst"
    const val PLATFORM = "android"

    // TV 端扫码登录参数，可为 0
    const val LOCAL_ID = 0
}
