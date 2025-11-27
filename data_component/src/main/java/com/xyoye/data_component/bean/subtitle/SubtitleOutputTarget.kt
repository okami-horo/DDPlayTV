package com.xyoye.data_component.bean.subtitle

import com.xyoye.data_component.enums.SubtitleViewType

data class SubtitleOutputTarget(
    val viewType: SubtitleViewType,
    val width: Int,
    val height: Int,
    val scale: Float = 1f,
    val rotation: Int = 0,
    val colorFormat: String? = null,
    val supportsHardwareBuffer: Boolean = false,
    val vsyncId: Long? = null
)
