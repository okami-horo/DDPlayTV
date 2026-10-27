package com.xyoye.common_component.source

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.utils.SupervisorScope
import kotlinx.coroutines.launch
import com.xyoye.common_component.source.media3.Media3LaunchParams

/**
 * Created by xyoye on 2021/11/14.
 */

class VideoSourceManager internal constructor(
    private val retireSource: (BaseVideoSource) -> Unit = { source ->
        SupervisorScope.IO.launch { (source as? StorageVideoSource)?.releasePlaybackResources() }
    }
) {
    companion object {
        @JvmStatic
        fun getInstance() = Holder.instance
    }

    private object Holder {
        val instance = VideoSourceManager()
    }

    private var activeSource: BaseVideoSource? = null
    @Volatile
    private var videoSource: BaseVideoSource? = null
    private var media3LaunchParams: Media3LaunchParams? = null

    fun setSource(source: BaseVideoSource) {
        val superseded = synchronized(this) {
            val previous = videoSource
            videoSource = source
            previous?.takeIf { it !== source && it !== activeSource }
        }
        superseded?.let(retireSource)
    }

    fun getSource() = videoSource

    /** Claim before backend setup so a concurrent pending request cannot retire this Source. */
    @Synchronized
    fun claimSource(): BaseVideoSource? = videoSource?.also { activeSource = it }

    @Synchronized
    fun onSourceApplied(source: BaseVideoSource) {
        val previousActive = activeSource
        activeSource = source
        if (videoSource == null || videoSource === previousActive || videoSource === source) {
            videoSource = source
        }
    }

    @Synchronized
    fun clearSource(source: BaseVideoSource) {
        if (activeSource === source) activeSource = null
        if (videoSource === source) videoSource = null
    }

    fun attachMedia3LaunchParams(params: Media3LaunchParams?) {
        media3LaunchParams = params
    }

    fun consumeMedia3LaunchParams(): Media3LaunchParams? {
        val params = media3LaunchParams
        media3LaunchParams = null
        return params
    }
}
