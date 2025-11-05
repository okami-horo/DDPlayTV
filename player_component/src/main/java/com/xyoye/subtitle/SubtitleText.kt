package com.xyoye.subtitle

data class SubtitleText(
    var text: String,

    var top: Boolean = false,

    var color: Int = 0,

    var x: Float? = null,

    var y: Float? = null,

    var align: Int? = null,

    var rotation: Float? = null,

    var playResX: Int? = null,

    var playResY: Int? = null,

    var lineIndex: Int = 0,

    var lineCount: Int = 1
)