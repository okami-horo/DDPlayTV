package com.xyoye.common_component.utils.danmu.live

import com.xyoye.data_component.data.bilibili.LiveDanmakuEvent

interface LiveDanmakuClient {
    interface Listener {
        fun onStateChanged(state: LiveDanmakuState)

        fun onEvent(event: LiveDanmakuEvent)
    }

    sealed interface LiveDanmakuState {
        data object Connecting : LiveDanmakuState

        data class Connected(
            val host: String
        ) : LiveDanmakuState

        data class Reconnecting(
            val attempt: Int,
            val delayMs: Long
        ) : LiveDanmakuState

        data class Disconnected(
            val reason: String?
        ) : LiveDanmakuState

        data class Error(
            val message: String
        ) : LiveDanmakuState
    }

    fun start()

    fun stop()
}
