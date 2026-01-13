package com.xyoye.data_component.data.baidupan.xpan

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BaiduPanUinfoResponse(
    override val errno: Int = 0,
    override val errmsg: String? = null,
    val uk: Long? = null,
    @Json(name = "netdisk_name")
    val netdiskName: String? = null,
    @Json(name = "baidu_name")
    val baiduName: String? = null,
    @Json(name = "avatar_url")
    val avatarUrl: String? = null,
    @Json(name = "vip_type")
    val vipType: Int? = null
) : BaiduPanErrnoResponse

@JsonClass(generateAdapter = true)
data class BaiduPanXpanError(
    override val errno: Int = 0,
    override val errmsg: String? = null
) : BaiduPanErrnoResponse

@JsonClass(generateAdapter = true)
data class BaiduPanXpanListResponse(
    override val errno: Int = 0,
    override val errmsg: String? = null,
    val list: List<BaiduPanXpanFileItem>? = null
) : BaiduPanErrnoResponse

@JsonClass(generateAdapter = true)
data class BaiduPanXpanSearchResponse(
    override val errno: Int = 0,
    override val errmsg: String? = null,
    val list: List<BaiduPanXpanFileItem>? = null
) : BaiduPanErrnoResponse

@JsonClass(generateAdapter = true)
data class BaiduPanXpanFileItem(
    @Json(name = "fs_id")
    val fsId: Long = 0L,
    val path: String = "",
    @Json(name = "server_filename")
    val serverFilename: String = "",
    val isdir: Int = 0,
    val size: Long? = null,
    @Json(name = "server_ctime")
    val serverCtime: Long? = null,
    @Json(name = "server_mtime")
    val serverMtime: Long? = null,
    val category: Int? = null
)

@JsonClass(generateAdapter = true)
data class BaiduPanFileMetasResponse(
    override val errno: Int = 0,
    override val errmsg: String? = null,
    val list: List<BaiduPanFileMetaItem>? = null
) : BaiduPanErrnoResponse

@JsonClass(generateAdapter = true)
data class BaiduPanFileMetaItem(
    @Json(name = "fs_id")
    val fsId: Long = 0L,
    val path: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val dlink: String? = null,
    val duration: Int? = null
)
