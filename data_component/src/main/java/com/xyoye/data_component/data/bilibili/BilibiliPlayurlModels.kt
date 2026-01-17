package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliPlayurlData(
    val dash: BilibiliDashData? = null,
    val durl: List<BilibiliDurlData> = emptyList(),
    @Json(name = "v_voucher")
    val vVoucher: String? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliDashData(
    val duration: Long = 0,
    @Json(name = "min_buffer_time")
    val minBufferTime: Double? = null,
    val video: List<BilibiliDashMediaData> = emptyList(),
    val audio: List<BilibiliDashMediaData> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliDashMediaData(
    val id: Int = 0,
    @Json(name = "codecid")
    val codecid: Int? = null,
    @Json(name = "base_url")
    val baseUrl: String = "",
    @Json(name = "backup_url")
    val backupUrl: List<String> = emptyList(),
    val bandwidth: Int = 0,
    @Json(name = "mime_type")
    val mimeType: String? = null,
    val codecs: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "frame_rate")
    val frameRate: String? = null,
    val sar: String? = null,
    @Json(name = "start_with_sap")
    val startWithSap: Int? = null,
    @Json(name = "segment_base")
    val segmentBase: BilibiliSegmentBaseData? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliSegmentBaseData(
    val initialization: String? = null,
    @Json(name = "index_range")
    val indexRange: String? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliDurlData(
    val order: Int = 0,
    val length: Long = 0,
    val size: Long = 0,
    @Json(name = "url")
    val url: String = "",
    @Json(name = "backup_url")
    val backupUrl: List<String> = emptyList()
)
