package com.xyoye.common_component.extension

import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.enums.MediaType

fun MediaType.toMedia3SourceType(): Media3SourceType =
    when (this) {
        MediaType.SCREEN_CAST -> Media3SourceType.CAST
        MediaType.LOCAL_STORAGE,
        MediaType.OTHER_STORAGE,
        MediaType.EXTERNAL_STORAGE -> Media3SourceType.DOWNLOAD
        else -> Media3SourceType.STREAM
    }
