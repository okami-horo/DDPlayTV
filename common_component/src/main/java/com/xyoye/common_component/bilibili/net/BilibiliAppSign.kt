package com.xyoye.common_component.bilibili.net

import com.xyoye.common_component.network.request.RequestParams
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Bilibili App/TV 接口签名（appkey + sign）。
 *
 * 主要用于 `passport-tv-login` 扫码登录链路，避免 Web 扫码接口在部分环境下触发风控/异常提示。
 */
object BilibiliAppSign {
    // 常用第三方客户端 appkey/appsec（公开信息）
    private const val APP_KEY = "dfca71928277209b"
    private const val APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"

    fun signForm(params: Map<String, Any>): RequestParams {
        val signed: RequestParams = hashMapOf()
        signed.putAll(params)
        signed["appkey"] = APP_KEY

        val sorted =
            signed
                .mapValues { (_, value) -> value.toString() }
                .toSortedMap()
        val raw =
            sorted.entries.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "utf-8")}"
            }
        signed["sign"] = md5Hex(raw + APP_SEC)
        return signed
    }

    private fun md5Hex(input: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
}

