package com.xyoye.common_component.source

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.media3.Media3LaunchParams

/**
 * Created by xyoye on 2021/11/14.
 */

class VideoSourceManager private constructor() {
    companion object {
        @JvmStatic
        fun getInstance() = Holder.instance
    }

    private object Holder {
        val instance = VideoSourceManager()
    }

    private var videoSource: BaseVideoSource? = null
    private var media3LaunchParams: Media3LaunchParams? = null

    fun setSource(source: BaseVideoSource) {
        videoSource = source
    }

    fun getSource() = videoSource

    fun attachMedia3LaunchParams(params: Media3LaunchParams?) {
        media3LaunchParams = params
    }

    fun consumeMedia3LaunchParams(): Media3LaunchParams? {
        val params = media3LaunchParams
        media3LaunchParams = null
        return params
    }
}
