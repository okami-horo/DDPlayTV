package com.xyoye.common_component.bilibili

import android.net.Uri

object BilibiliKeys {
    private const val SCHEME = "bilibili"
    private const val HOST_ARCHIVE = "archive"
    private const val HOST_LIVE = "live"
    private const val QUERY_CID = "cid"

    sealed interface Key

    data class ArchiveKey(
        val bvid: String,
        val cid: Long? = null,
    ) : Key

    data class LiveKey(
        val roomId: Long,
    ) : Key

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

    fun liveRoomKey(roomId: Long): String =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(HOST_LIVE)
            .appendPath(roomId.toString())
            .build()
            .toString()

    fun parse(uniqueKey: String): Key? =
        runCatching {
            val uri = Uri.parse(uniqueKey)
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return@runCatching null
            val authority = uri.authority.orEmpty()

            when {
                authority.equals(HOST_ARCHIVE, ignoreCase = true) -> {
                    val bvid = uri.pathSegments.firstOrNull().orEmpty()
                    if (bvid.isBlank()) return@runCatching null
                    val cid = uri.getQueryParameter(QUERY_CID)?.toLongOrNull()?.takeIf { it > 0 }
                    ArchiveKey(bvid = bvid, cid = cid)
                }

                authority.equals(HOST_LIVE, ignoreCase = true) -> {
                    val roomId = uri.pathSegments.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 }
                        ?: return@runCatching null
                    LiveKey(roomId = roomId)
                }

                else -> null
            }
        }.getOrNull()
}
