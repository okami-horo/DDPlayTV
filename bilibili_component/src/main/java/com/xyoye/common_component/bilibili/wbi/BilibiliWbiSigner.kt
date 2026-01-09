package com.xyoye.common_component.bilibili.wbi

import com.tencent.mmkv.MMKV
import com.xyoye.common_component.extension.toMd5String
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * WBI key 缓存与签名实现。
 *
 * - img_key/sub_key 来源：`/x/web-interface/nav` 的 `wbi_img`
 * - mixin_key 由 img_key + sub_key 按固定序列混排后取前 32 位
 * - w_rid = md5( query(with wts, sorted) + mixin_key )
 *
 * 参考：`.tmp/bilibili-API-collect/docs/misc/sign/wbi.md`
 */
object BilibiliWbiSigner {
    private const val MMKV_ID = "bilibili_wbi_signer"

    private const val KEY_IMG = "img_key"
    private const val KEY_SUB = "sub_key"
    private const val KEY_UPDATED_AT = "updated_at"

    private const val KEY_TTL_MS = 24L * 60L * 60L * 1000L

    data class WbiKeys(
        val imgKey: String,
        val subKey: String,
        val updatedAt: Long,
    )

    suspend fun sign(
        params: Map<String, Any?>,
        fetchKeys: suspend () -> WbiKeys?
    ): Map<String, Any> {
        val nowSec = System.currentTimeMillis() / 1000
        val toSign = params.toMutableMap()
        toSign["wts"] = nowSec

        val keys = getOrFetchKeys(fetchKeys) ?: return toSign.filterValuesNotNull()
        val mixinKey = mixinKey(keys.imgKey, keys.subKey)
        val query = buildQuery(toSign.filterValuesNotNull())
        val rid = (query + mixinKey).toMd5String().orEmpty()
        toSign["w_rid"] = rid
        return toSign.filterValuesNotNull()
    }

    fun extractKeyFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val normalized =
            runCatching {
                val decoded = URLDecoder.decode(url, Charsets.UTF_8.name())
                URI.create(decoded).path.orEmpty()
            }.getOrNull().orEmpty()
        val name = normalized.substringAfterLast('/').substringBeforeLast('.')
        return name.ifBlank { null }
    }

    private suspend fun getOrFetchKeys(fetchKeys: suspend () -> WbiKeys?): WbiKeys? {
        val cached = readCache()
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= KEY_TTL_MS) {
            return cached
        }
        val refreshed = fetchKeys()
        if (refreshed != null) {
            writeCache(refreshed)
            return refreshed
        }
        return cached
    }

    private fun mixinKey(
        imgKey: String,
        subKey: String
    ): String {
        val origin = imgKey + subKey
        val mixed =
            MIXIN_KEY_ENC_TAB
                .asSequence()
                .mapNotNull { index -> origin.getOrNull(index) }
                .joinToString("")
        return mixed.take(32)
    }

    private fun buildQuery(params: Map<String, Any>): String {
        val sorted = params.toSortedMap()
        val raw =
            sorted.entries.joinToString("&") { (k, v) ->
                val value = v.toString()
                val encoded = encodeURIComponent(value)
                "${k}=$encoded"
            }
        // WBI 签名要求移除特定字符，避免不同端编码差异导致签名不一致
        return raw.replace("[!'()*]".toRegex(), "")
    }

    private fun encodeURIComponent(input: String): String =
        URLEncoder
            .encode(input, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~")

    private fun readCache(): WbiKeys? {
        val kv = mmkv()
        val imgKey = kv.decodeString(KEY_IMG).orEmpty()
        val subKey = kv.decodeString(KEY_SUB).orEmpty()
        val updatedAt = kv.decodeLong(KEY_UPDATED_AT, 0L)
        if (imgKey.isBlank() || subKey.isBlank() || updatedAt <= 0L) return null
        return WbiKeys(imgKey, subKey, updatedAt)
    }

    private fun writeCache(keys: WbiKeys) {
        val kv = mmkv()
        kv.encode(KEY_IMG, keys.imgKey)
        kv.encode(KEY_SUB, keys.subKey)
        kv.encode(KEY_UPDATED_AT, keys.updatedAt)
    }

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun <K, V : Any> Map<K, V?>.filterValuesNotNull(): Map<K, V> =
        entries
            .filter { it.value != null }
            .associate { it.key to it.value!! }

    private val MIXIN_KEY_ENC_TAB =
        intArrayOf(
            46,
            47,
            18,
            2,
            53,
            8,
            23,
            32,
            15,
            50,
            10,
            31,
            58,
            3,
            45,
            35,
            27,
            43,
            5,
            49,
            33,
            9,
            42,
            19,
            29,
            28,
            14,
            39,
            12,
            38,
            41,
            13,
            37,
            48,
            7,
            16,
            24,
            55,
            40,
            61,
            26,
            17,
            0,
            1,
            60,
            51,
            30,
            4,
            22,
            25,
            54,
            21,
            56,
            59,
            6,
            63,
            57,
            62,
            11,
            36,
            20,
            34,
            44,
            52,
        )
}
