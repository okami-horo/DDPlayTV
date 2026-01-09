package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackEvent

class BilibiliPlaybackAddon(
    private val storageId: Int,
    private val uniqueKey: String,
) : PlaybackAddon {
    override val addonId: String = "bilibili/playback"

    override fun onEvent(event: PlaybackEvent) {
    }
}
