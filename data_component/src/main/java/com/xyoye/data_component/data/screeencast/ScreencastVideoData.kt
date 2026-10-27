package com.xyoye.data_component.data.screeencast

import android.os.Parcelable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize

/**
 * Created by xyoye on 2022/9/16
 */

@Parcelize
@JsonClass(generateAdapter = true)
data class ScreencastData(
    val port: Int,
    val relatedVideos: List<ScreencastVideoData>,
    val playUniqueKey: String,
    val httpHeader: Map<String, String>? = null,
    @Json(ignore = true) var ip: String? = null
) : Parcelable

@Parcelize
@JsonClass(generateAdapter = true)
data class ScreencastVideoData(
    val title: String = "",
    val uniqueKey: String = "",
    val episodeId: String? = null,
    val danmuFileName: String? = null,
    val subtitleFileName: String? = null,
    val position: Long = 0L,
    val duration: Long = 0L
) : Parcelable
