package com.xyoye.common_component.playback.addon

import com.xyoye.data_component.enums.MediaType

data class PlaybackIdentity(
    val storageId: Int,
    val uniqueKey: String,
    val mediaType: MediaType,
    val storagePath: String?,
    val videoTitle: String,
    val videoUrl: String,
)

