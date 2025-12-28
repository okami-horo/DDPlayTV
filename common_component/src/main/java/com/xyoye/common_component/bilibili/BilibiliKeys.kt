package com.xyoye.common_component.bilibili

import android.net.Uri

object BilibiliKeys {
    private const val SCHEME = "bilibili"
    private const val HOST_ARCHIVE = "archive"
    private const val QUERY_CID = "cid"

    data class ArchiveKey(
        val bvid: String,
        val cid: Long? = null,
    )

    fun archiveDirectoryKey(bvid: String): String =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(HOST_ARCHIVE)
            .appendPath(bvid)
            .build()
            .toString()

    fun archivePartKey(
        bvid: String,
        cid: Long,
    ): String =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(HOST_ARCHIVE)
            .appendPath(bvid)
            .appendQueryParameter(QUERY_CID, cid.toString())
            .build()
            .toString()

    fun parse(uniqueKey: String): ArchiveKey? =
        runCatching {
            val uri = Uri.parse(uniqueKey)
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return@runCatching null
            if (!uri.authority.equals(HOST_ARCHIVE, ignoreCase = true)) return@runCatching null
            val bvid = uri.pathSegments.firstOrNull().orEmpty()
            if (bvid.isBlank()) return@runCatching null
            val cid = uri.getQueryParameter(QUERY_CID)?.toLongOrNull()?.takeIf { it > 0 }
            ArchiveKey(bvid = bvid, cid = cid)
        }.getOrNull()
}

