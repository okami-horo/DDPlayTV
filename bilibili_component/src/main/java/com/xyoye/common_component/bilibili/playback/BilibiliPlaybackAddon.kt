package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackEvent
import com.xyoye.data_component.enums.MediaType

class BilibiliPlaybackAddon(
    private val storageId: Int,
    private val uniqueKey: String,
) : PlaybackAddon {
    override val addonId: String = "bilibili/playback"

    override fun onEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.Progress -> {
                if (!canHandle(event)) return
                BilibiliPlaybackHeartbeat.onProgress(
                    storageId = storageId,
                    uniqueKey = uniqueKey,
                    mediaType = event.identity.mediaType,
                    positionMs = event.positionMs,
                    isPlaying = event.isPlaying,
                )
            }

            is PlaybackEvent.PlayStateChanged -> {
                if (!canHandle(event)) return
                BilibiliPlaybackHeartbeat.onPlayStateChanged(
                    storageId = storageId,
                    uniqueKey = uniqueKey,
                    mediaType = event.identity.mediaType,
                    playState = event.playState,
                    positionMs = event.positionMs,
                )
            }

            else -> Unit
        }
    }

    private fun canHandle(event: PlaybackEvent.Progress): Boolean {
        if (event.identity.mediaType != MediaType.BILIBILI_STORAGE) return false
        if (event.identity.storageId != storageId) return false
        if (event.identity.uniqueKey != uniqueKey) return false
        return true
    }

    private fun canHandle(event: PlaybackEvent.PlayStateChanged): Boolean {
        if (event.identity.mediaType != MediaType.BILIBILI_STORAGE) return false
        if (event.identity.storageId != storageId) return false
        if (event.identity.uniqueKey != uniqueKey) return false
        return true
    }
}
