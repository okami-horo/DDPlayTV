package com.xyoye.common_component.source.base

import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackAddonProvider
import com.xyoye.common_component.source.inter.ExtraSource
import com.xyoye.common_component.source.inter.VideoSource
import com.xyoye.data_component.bean.LocalDanmuBean

/**
 * Created by xyoye on 2022/1/11
 */
abstract class BaseVideoSource(
    index: Int,
    videoSources: List<*>
) : GroupVideoSource(index, videoSources),
    VideoSource,
    ExtraSource,
    PlaybackAddonProvider {
    override fun getDanmu(): LocalDanmuBean? = null

    override fun setDanmu(danmu: LocalDanmuBean?) {
    }

    override fun getSubtitlePath(): String? = null

    override fun setSubtitlePath(path: String?) {
    }

    override fun getAudioPath(): String? = null

    override fun setAudioPath(path: String?) {
    }

    override fun getHttpHeader(): Map<String, String>? = null

    override fun getStoragePath(): String? = null

    override fun getPlaybackAddon(): PlaybackAddon? = null
}
