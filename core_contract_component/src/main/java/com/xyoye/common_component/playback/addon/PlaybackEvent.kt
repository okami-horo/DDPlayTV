package com.xyoye.common_component.playback.addon

import com.xyoye.data_component.enums.PlayState

sealed class PlaybackEvent {
    /**
     * 播放源切换/初始化完成后派发。
     * 用于：live 会话统计重置、addon 状态机初始化等。
     */
    data class SourceChanged(
        val identity: PlaybackIdentity,
        val httpHeader: Map<String, String>?
    ) : PlaybackEvent()

    data class PlayStateChanged(
        val identity: PlaybackIdentity,
        val playState: PlayState,
        val positionMs: Long
    ) : PlaybackEvent()

    data class Progress(
        val identity: PlaybackIdentity,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean
    ) : PlaybackEvent()

    data class PlaybackError(
        val identity: PlaybackIdentity,
        val throwable: Throwable?,
        val scene: String,
        /**
         * 播放器侧可观测诊断信息（来源可能包含 Media3/播放器内核）。
         * 仅传递事实，不在通用层固化“某个存储专属字段”。
         */
        val diagnostics: Map<String, String> = emptyMap()
    ) : PlaybackEvent()
}
