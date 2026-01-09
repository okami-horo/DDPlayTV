package com.xyoye.common_component.playback.addon

/**
 * 播放期扩展的可选生命周期回调。
 *
 * - Addon 的生命周期应与播放源绑定，切源时 addon 一并替换。
 * - 当播放器不再持有该 addon（退出播放/切到其它播放身份）时，应调用 [onRelease] 做清理。
 * - 实现需要保证幂等（允许多次调用）。
 */
interface PlaybackReleasableAddon : PlaybackAddon {
    fun onRelease()
}

