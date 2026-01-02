package com.xyoye.common_component.bilibili.live.danmaku

data class LiveDanmakuPacket(
    val packetLen: Int,
    val headerLen: Int,
    val protocolVer: Int,
    val operation: Int,
    val sequence: Int,
    val body: ByteArray,
)

