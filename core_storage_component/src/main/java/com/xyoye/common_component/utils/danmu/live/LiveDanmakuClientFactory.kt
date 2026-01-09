package com.xyoye.common_component.utils.danmu.live

import com.xyoye.common_component.bilibili.BilibiliDanmakuBlockPreferencesStore
import com.xyoye.common_component.bilibili.live.danmaku.LiveDanmakuSocketClient
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.data.bilibili.LiveDanmakuEvent
import kotlinx.coroutines.CoroutineScope

object LiveDanmakuClientFactory {
    fun create(
        resource: DanmuTrackResource,
        scope: CoroutineScope,
        listener: LiveDanmakuClient.Listener,
    ): LiveDanmakuClient? =
        when (resource) {
            is DanmuTrackResource.BilibiliLive -> {
                val prefs = BilibiliDanmakuBlockPreferencesStore.read(resource.storageKey)
                val delegateListener =
                    object : LiveDanmakuSocketClient.Listener {
                        override fun onStateChanged(state: LiveDanmakuSocketClient.LiveDanmakuState) {
                            listener.onStateChanged(state.toContractState())
                        }

                        override fun onEvent(event: LiveDanmakuEvent) {
                            if (event is LiveDanmakuEvent.Danmaku && prefs.aiSwitch) {
                                val effectiveLevel = if (prefs.aiLevel == 0) 3 else prefs.aiLevel
                                if (event.recommendScore < effectiveLevel) {
                                    return
                                }
                            }
                            listener.onEvent(event)
                        }
                    }

                LiveDanmakuSocketClient(
                    storageKey = resource.storageKey,
                    roomId = resource.roomId,
                    scope = scope,
                    listener = delegateListener,
                ).asClient()
            }

            else -> null
        }

    private fun LiveDanmakuSocketClient.asClient(): LiveDanmakuClient =
        object : LiveDanmakuClient {
            override fun start() = this@asClient.start()

            override fun stop() = this@asClient.stop()
        }

    private fun LiveDanmakuSocketClient.LiveDanmakuState.toContractState(): LiveDanmakuClient.LiveDanmakuState =
        when (this) {
            LiveDanmakuSocketClient.LiveDanmakuState.Connecting -> LiveDanmakuClient.LiveDanmakuState.Connecting
            is LiveDanmakuSocketClient.LiveDanmakuState.Connected ->
                LiveDanmakuClient.LiveDanmakuState.Connected(host = host)
            is LiveDanmakuSocketClient.LiveDanmakuState.Reconnecting ->
                LiveDanmakuClient.LiveDanmakuState.Reconnecting(attempt = attempt, delayMs = delayMs)
            is LiveDanmakuSocketClient.LiveDanmakuState.Disconnected ->
                LiveDanmakuClient.LiveDanmakuState.Disconnected(reason = reason)
            is LiveDanmakuSocketClient.LiveDanmakuState.Error -> LiveDanmakuClient.LiveDanmakuState.Error(message = message)
        }
}

