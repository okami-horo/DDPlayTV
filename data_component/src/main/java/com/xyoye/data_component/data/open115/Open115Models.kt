package com.xyoye.data_component.data.open115

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

interface Open115ProApiResponse {
    val state: Boolean
    val code: Int
    val message: String?
}

interface Open115PassportResponse {
    val state: Int
    val code: Int
    val message: String?
    val errno: Int
    val error: String?
}

@JsonClass(generateAdapter = true)
data class Open115ProApiEnvelope(
    override val state: Boolean = false,
    override val code: Int = 0,
    override val message: String? = null,
    val data: Any? = null
) : Open115ProApiResponse

@JsonClass(generateAdapter = true)
data class Open115PassportEnvelope(
    override val state: Int = 0,
    override val code: Int = 0,
    override val message: String? = null,
    override val errno: Int = 0,
    override val error: String? = null,
    val data: Any? = null
) : Open115PassportResponse

@JsonClass(generateAdapter = true)
data class Open115RefreshTokenData(
    @Json(name = "access_token")
    val accessToken: String = "",
    @Json(name = "refresh_token")
    val refreshToken: String = "",
    @Json(name = "expires_in")
    val expiresIn: Int = 0
)

@JsonClass(generateAdapter = true)
data class Open115RefreshTokenResponse(
    override val state: Int = 0,
    override val code: Int = 0,
    override val message: String? = null,
    override val errno: Int = 0,
    override val error: String? = null,
    val data: Open115RefreshTokenData? = null
) : Open115PassportResponse

@JsonClass(generateAdapter = true)
data class Open115UserInfoData(
    @Json(name = "user_id")
    val userId: String? = null,
    @Json(name = "user_name")
    val userName: String? = null,
    @Json(name = "user_face_s")
    val userFaceSmall: String? = null,
    @Json(name = "user_face_m")
    val userFaceMedium: String? = null,
    @Json(name = "user_face_l")
    val userFaceLarge: String? = null,
    @Json(name = "rt_space_info")
    val spaceInfo: Open115SpaceInfo? = null,
    @Json(name = "vip_info")
    val vipInfo: Open115VipInfo? = null
) {
    @JsonClass(generateAdapter = true)
    data class Open115SpaceInfo(
        @Json(name = "all_total")
        val allTotal: Open115SpaceSize? = null,
        @Json(name = "all_use")
        val allUse: Open115SpaceSize? = null,
        @Json(name = "all_remain")
        val allRemain: Open115SpaceSize? = null
    )

    @JsonClass(generateAdapter = true)
    data class Open115SpaceSize(
        val size: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class Open115VipInfo(
        @Json(name = "level_name")
        val levelName: String? = null,
        val expire: String? = null
    )
}

@JsonClass(generateAdapter = true)
data class Open115UserInfoResponse(
    override val state: Boolean = false,
    override val code: Int = 0,
    override val message: String? = null,
    val data: Open115UserInfoData? = null
) : Open115ProApiResponse

@JsonClass(generateAdapter = true)
data class Open115FileItem(
    val fid: String? = null,
    val pid: String? = null,
    val fc: String? = null,
    val fn: String? = null,
    val pc: String? = null,
    val sha1: String? = null,
    val fs: Long? = null,
    val upt: Long? = null,
    val uet: Long? = null,
    val uppt: Long? = null,
    val isv: Long? = null,
    val ico: String? = null,
    val thumb: String? = null
)

@JsonClass(generateAdapter = true)
data class Open115ListFilesResponse(
    override val state: Boolean = false,
    override val code: Int = 0,
    override val message: String? = null,
    val data: List<Open115FileItem>? = null,
    val count: Long? = null,
    val offset: Long? = null,
    val limit: Int? = null
) : Open115ProApiResponse

@JsonClass(generateAdapter = true)
data class Open115DownUrlLink(
    val url: String? = null
)

@JsonClass(generateAdapter = true)
data class Open115DownUrlItem(
    @Json(name = "file_name")
    val fileName: String? = null,
    @Json(name = "file_size")
    val fileSize: Long? = null,
    @Json(name = "pick_code")
    val pickCode: String? = null,
    val sha1: String? = null,
    val url: Open115DownUrlLink? = null
)

@JsonClass(generateAdapter = true)
data class Open115DownUrlResponse(
    override val state: Boolean = false,
    override val code: Int = 0,
    override val message: String? = null,
    val data: Map<String, Open115DownUrlItem>? = null
) : Open115ProApiResponse

@JsonClass(generateAdapter = true)
data class Open115SearchItem(
    @Json(name = "file_id")
    val fileId: String? = null,
    @Json(name = "parent_id")
    val parentId: String? = null,
    @Json(name = "file_name")
    val fileName: String? = null,
    @Json(name = "file_size")
    val fileSize: String? = null,
    @Json(name = "pick_code")
    val pickCode: String? = null,
    @Json(name = "file_category")
    val fileCategory: String? = null,
    val ico: String? = null
)

@JsonClass(generateAdapter = true)
data class Open115SearchResponse(
    override val state: Boolean = false,
    override val code: Int = 0,
    override val message: String? = null,
    val data: List<Open115SearchItem>? = null,
    val count: Long? = null,
    val offset: Long? = null,
    val limit: Long? = null
) : Open115ProApiResponse

@JsonClass(generateAdapter = true)
data class Open115FolderInfoData(
    @Json(name = "file_id")
    val fileId: String? = null,
    @Json(name = "parent_id")
    val parentId: String? = null,
    @Json(name = "file_name")
    val fileName: String? = null
)

@JsonClass(generateAdapter = true)
data class Open115FolderInfoResponse(
    override val state: Boolean = false,
    override val code: Int = 0,
    override val message: String? = null,
    val data: Open115FolderInfoData? = null
) : Open115ProApiResponse
