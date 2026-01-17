package com.xyoye.data_component.enums

import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.bean.LocalDanmuBean

/**
 * Created by xyoye on 2024/1/23.
 */

enum class TrackType {
    DANMU,

    SUBTITLE,

    AUDIO,

    VIDEO;

    fun getSubtitle(value: Any?): String? {
        if (value != null && value is String && this == SUBTITLE) {
            return value
        }
        return null
    }

    fun getAudio(value: Any?): String? {
        if (value != null && value is String && this == AUDIO) {
            return value
        }
        return null
    }

    fun getDanmu(value: Any?): LocalDanmuBean? = getDanmuResource(value)?.let { (it as? DanmuTrackResource.LocalFile)?.danmu }

    fun getDanmuResource(value: Any?): DanmuTrackResource? {
        if (this != DANMU || value == null) return null
        return when (value) {
            is DanmuTrackResource -> value
            is LocalDanmuBean -> DanmuTrackResource.LocalFile(value)
            else -> null
        }
    }
}
