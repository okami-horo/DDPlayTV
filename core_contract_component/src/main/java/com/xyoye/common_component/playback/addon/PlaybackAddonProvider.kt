package com.xyoye.common_component.playback.addon

/**
 * 为播放源提供可选的播放期扩展能力（Playback Addon）。
 *
 * 说明：
 * - 该接口属于 Contract 层，player_component 只依赖该接口，不依赖任何具体存储实现（如 bilibili）。
 * - Addon 的生命周期应与播放源绑定，切源时 addon 一并替换。
 */
interface PlaybackAddonProvider {
    fun getPlaybackAddon(): PlaybackAddon?
}

