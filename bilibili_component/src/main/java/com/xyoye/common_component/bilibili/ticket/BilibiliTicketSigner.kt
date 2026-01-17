package com.xyoye.common_component.bilibili.ticket

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bilibili bili_ticket（WebTicket）签名工具。
 *
 * 参考：`.tmp/bilibili-API-collect/docs/misc/sign/bili_ticket.md`
 */
object BilibiliTicketSigner {
    private const val KEY_ID = "ec02"
    private const val HMAC_KEY = "XgwSnGZ1p"

    data class SignedParams(
        val keyId: String,
        val timestampSec: Long,
        val hexsign: String
    )

    fun sign(timestampSec: Long = System.currentTimeMillis() / 1000): SignedParams {
        val message = "ts$timestampSec"
        val hexsign = hmacSha256Hex(HMAC_KEY, message)
        return SignedParams(
            keyId = KEY_ID,
            timestampSec = timestampSec,
            hexsign = hexsign,
        )
    }

    private fun hmacSha256Hex(
        key: String,
        message: String
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
