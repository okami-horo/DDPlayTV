package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliPersistedCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean
)
