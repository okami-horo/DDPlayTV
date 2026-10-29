package com.xyoye.common_component.config

import com.xyoye.data_component.enums.LocalProxyMode
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

@MMKVKotlinClass(className = "PlayerConfig")
object PlayerConfigTable {
    // 是否使用surface view
    @MMKVFiled
    const val useSurfaceView = true

    // 使用播放器类型
    @MMKVFiled
    val usePlayerType = PlayerType.TYPE_EXO_PLAYER.value

    // 视频倍速
    @MMKVFiled
    val newVideoSpeed = 1f

    // 自动播放下一集
    @MMKVFiled
    val autoPlayNext = true

    // 后台播放
    @MMKVFiled
    val backgroundPlay = false

    // MPV 本地 HTTP 代理：Range 请求最小间隔（毫秒），用于降低上游风控触发概率
    @MMKVFiled
    val mpvProxyRangeMinIntervalMs = 1000

    // MPV 本地 HTTP 代理：开关策略（关闭 / 自动 / 强制）
    @MMKVFiled
    val mpvLocalProxyMode = LocalProxyMode.AUTO.value

    // Media3(Exo) 本地 HTTP 代理：Range 请求最小间隔（毫秒），用于降低上游风控触发概率
    @MMKVFiled
    val exoProxyRangeMinIntervalMs = 200

    // Media3(Exo) 本地 HTTP 代理：开关策略（关闭 / 自动 / 强制）
    @MMKVFiled
    val exoLocalProxyMode = LocalProxyMode.AUTO.value

    // MPV 音频输出（ao）：default / opensles / audiotrack
    @MMKVFiled
    val mpvAudioOutput = "default"

    // MPV 视频同步（video-sync）：default / audio / display-resample / ...
    @MMKVFiled
    val mpvVideoSync = "default"
}
