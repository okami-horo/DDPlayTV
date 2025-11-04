package com.xyoye.subtitle

data class SubtitleText(
    var text: String,

    var top: Boolean = false,

    var color: Int = 0,

    var x: Float? = null,

    var y: Float? = null,

    var align: Int? = null,

    var rotation: Float? = null
)