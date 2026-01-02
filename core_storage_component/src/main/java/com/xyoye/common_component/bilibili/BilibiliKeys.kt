package com.xyoye.common_component.bilibili

import android.net.Uri

object BilibiliKeys {
    private const val SCHEME = "bilibili"
    private const val HOST_ARCHIVE = "archive"
    private const val HOST_LIVE = "live"
    private const val HOST_PGC = "pgc"

    private const val PATH_PGC_EPISODE = "ep"
    private const val PATH_PGC_SEASON = "season"

    private const val QUERY_CID = "cid"
    private const val QUERY_SEASON_ID = "sid"
    private const val QUERY_AVID = "aid"

    sealed interface Key

    data class ArchiveKey(
        val bvid: String,
        val cid: Long? = null,
    ) : Key

    data class LiveKey(
        val roomId: Long,
    ) : Key

    data class PgcEpisodeKey(
        val epId: Long,
        val cid: Long,
        val seasonId: Long? = null,
        val avid: Long? = null,
    ) : Key

    data class PgcSeasonKey(
        val seasonId: Long,
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

    fun pgcEpisodeKey(
        epId: Long,
        cid: Long,
        seasonId: Long? = null,
        avid: Long? = null,
    ): String =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(HOST_PGC)
            .appendPath(PATH_PGC_EPISODE)
            .appendPath(epId.toString())
            .appendQueryParameter(QUERY_CID, cid.toString())
            .apply {
                seasonId?.takeIf { it > 0 }?.let {
                    appendQueryParameter(QUERY_SEASON_ID, it.toString())
                }
                avid?.takeIf { it > 0 }?.let {
                    appendQueryParameter(QUERY_AVID, it.toString())
                }
            }.build()
            .toString()

    fun pgcSeasonKey(seasonId: Long): String =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(HOST_PGC)
            .appendPath(PATH_PGC_SEASON)
            .appendPath(seasonId.toString())
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

                authority.equals(HOST_PGC, ignoreCase = true) -> {
                    when {
                        uri.pathSegments.getOrNull(0).equals(PATH_PGC_EPISODE, ignoreCase = true) -> {
                            val epId = uri.pathSegments.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                                ?: return@runCatching null
                            val cid = uri.getQueryParameter(QUERY_CID)?.toLongOrNull()?.takeIf { it > 0 }
                                ?: return@runCatching null
                            val seasonId = uri.getQueryParameter(QUERY_SEASON_ID)?.toLongOrNull()?.takeIf { it > 0 }
                            val avid = uri.getQueryParameter(QUERY_AVID)?.toLongOrNull()?.takeIf { it > 0 }
                            PgcEpisodeKey(
                                epId = epId,
                                cid = cid,
                                seasonId = seasonId,
                                avid = avid,
                            )
                        }

                        uri.pathSegments.getOrNull(0).equals(PATH_PGC_SEASON, ignoreCase = true) -> {
                            val seasonId = uri.pathSegments.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                                ?: return@runCatching null
                            PgcSeasonKey(seasonId = seasonId)
                        }

                        else -> null
                    }
                }

                else -> null
            }
        }.getOrNull()
}
