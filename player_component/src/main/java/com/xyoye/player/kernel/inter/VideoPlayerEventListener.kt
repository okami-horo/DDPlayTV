package com.xyoye.player.kernel.inter

import com.xyoye.subtitle.MixedSubtitle

/**
 * Created by xyoye on 2020/10/29.
 */

interface VideoPlayerEventListener {
    fun onPrepared()

    fun onError(e: Exception? = null)

    fun onCompletion()

    fun onVideoSizeChange(
        width: Int,
        height: Int
    )

    fun onInfo(
        what: Int,
        extra: Int
    )

    fun onSubtitleTextOutput(subtitle: MixedSubtitle)

    companion object {
        val NO_OP: VideoPlayerEventListener =
            object : VideoPlayerEventListener {
                override fun onPrepared() = Unit

                override fun onError(e: Exception?) = Unit

                override fun onCompletion() = Unit

                override fun onVideoSizeChange(
                    width: Int,
                    height: Int
                ) = Unit

                override fun onInfo(
                    what: Int,
                    extra: Int
                ) = Unit

                override fun onSubtitleTextOutput(subtitle: MixedSubtitle) = Unit
            }
    }
}
