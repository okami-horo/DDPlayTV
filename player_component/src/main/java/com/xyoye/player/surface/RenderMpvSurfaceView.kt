package com.xyoye.player.surface

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xyoye.data_component.enums.VideoScreenScale
import com.xyoye.player.kernel.impl.mpv.MpvVideoPlayer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.RenderMeasureHelper

class RenderMpvSurfaceView(
    context: Context
) : SurfaceView(context),
    InterSurfaceView {
    private val measureHelper = RenderMeasureHelper()
    private lateinit var videoPlayer: AbstractVideoPlayer

    private val surfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                if (this@RenderMpvSurfaceView::videoPlayer.isInitialized) {
                    (videoPlayer as? MpvVideoPlayer)?.setSurfaceSize(width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                if (this@RenderMpvSurfaceView::videoPlayer.isInitialized) {
                    videoPlayer.setSurface(holder.surface)
                    val frame = holder.surfaceFrame
                    if (frame.width() > 0 && frame.height() > 0) {
                        (videoPlayer as? MpvVideoPlayer)?.setSurfaceSize(frame.width(), frame.height())
                    }
                }
            }
        }

    init {
        holder.addCallback(surfaceCallback)
    }

    override fun attachPlayer(player: AbstractVideoPlayer) {
        videoPlayer = player
    }

    override fun setVideoSize(
        videoWidth: Int,
        videoHeight: Int
    ) {
        if (videoWidth > 0 && videoHeight > 0) {
            measureHelper.mVideoWidth = videoWidth
            measureHelper.mVideoHeight = videoHeight
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        measureHelper.mVideoDegree = degree
        if (rotation != degree.toFloat()) {
            rotation = degree.toFloat()
            requestLayout()
        }
    }

    override fun setScaleType(screenScale: VideoScreenScale) {
        measureHelper.mScreenScale = screenScale
        requestLayout()
    }

    override fun getView() = this

    override fun refresh() {
        post { requestLayout() }
    }

    override fun release() {
        holder.removeCallback(surfaceCallback)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val measuredSize = measureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredSize[0], measuredSize[1])
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        holder.removeCallback(surfaceCallback)
    }
}
