package com.xyoye.common_component.config

import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

@MMKVKotlinClass(className = "PlayerConfig")
object PlayerConfigTable {
    //是否允许屏幕旋转
    @MMKVFiled
    const val allowOrientationChange = true

    //是否使用surface view
    @MMKVFiled
    const val useSurfaceView = false

    //使用播放器类型
    @MMKVFiled
    val usePlayerType = PlayerType.TYPE_VLC_PLAYER.value

    //VLC内核硬件加速
    @MMKVFiled
    val useVLCHWDecoder = VLCHWDecode.HW_ACCELERATION_AUTO.value

    //VLC音频输出
    @MMKVFiled
    val useVLCAudioOutput = VLCAudioOutput.AUTO.value

    //VLC音频兼容模式（自动降级）
    @MMKVFiled
    val vlcAudioCompatMode = false

    //VLC音频能力是否已探测
    @MMKVFiled
    val vlcAudioCompatChecked = false

    //视频倍速
    @MMKVFiled
    val newVideoSpeed = 1f

    //视频长按速率
    @MMKVFiled
    val pressVideoSpeed = 2f

    //自动播放下一集
    @MMKVFiled
    val autoPlayNext = true

    //后台播放
    @MMKVFiled
    val backgroundPlay = false

    //MPV 本地 HTTP 代理：Range 请求最小间隔（毫秒），用于降低上游风控触发概率
    @MMKVFiled
    val mpvProxyRangeMinIntervalMs = 200
}
