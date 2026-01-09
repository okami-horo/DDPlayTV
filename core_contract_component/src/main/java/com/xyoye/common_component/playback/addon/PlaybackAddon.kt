package com.xyoye.common_component.playback.addon

interface PlaybackAddon {
    /**
     * Addon 唯一标识，用于日志与调试。
     * 建议格式："{mediaType}/{feature}"，例如 "bilibili/heartbeat"。
     */
    val addonId: String

    /**
     * 播放器派发的统一事件入口。
     * Addon 内部自行过滤关心的事件类型。
     */
    fun onEvent(event: PlaybackEvent)
}

