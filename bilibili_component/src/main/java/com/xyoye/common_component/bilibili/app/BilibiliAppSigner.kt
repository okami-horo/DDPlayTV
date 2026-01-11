package com.xyoye.common_component.bilibili.app

import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.network.request.RequestParams
import java.net.URLEncoder

/**
 * APP API 签名实现（appkey/appsec + md5）。
 *
 * 参考：`.tmp/bilibili-API-collect/docs/misc/sign/APP.md`
 */
object BilibiliAppSigner {
    fun sign(
        params: Map<String, Any?>,
        appKey: String,
        appSec: String,
        tsSeconds: Long = System.currentTimeMillis() / 1000
    ): RequestParams {
        val toSign = params.toMutableMap()
        toSign["appkey"] = appKey
        if (toSign["ts"] == null) {
            toSign["ts"] = tsSeconds
        }
        toSign.remove("sign")

        val query = buildQuery(toSign.filterValuesNotNull())
        val sign = (query + appSec).toMd5String().orEmpty()

        val signed: RequestParams = hashMapOf()
        signed.putAll(toSign.filterValuesNotNull())
        signed["sign"] = sign
        return signed
    }

    private fun buildQuery(params: Map<String, Any>): String {
        val sorted = params.toSortedMap()
        return sorted.entries.joinToString("&") { (k, v) ->
            val encodedKey = encodeURIComponent(k)
            val encodedValue = encodeURIComponent(v.toString())
            "$encodedKey=$encodedValue"
        }
    }

    private fun encodeURIComponent(input: String): String = URLEncoder.encode(input, Charsets.UTF_8.name())

    private fun <K, V : Any> Map<K, V?>.filterValuesNotNull(): Map<K, V> =
        entries
            .filter { it.value != null }
            .associate { it.key to it.value!! }
}
