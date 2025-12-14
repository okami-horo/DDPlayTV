package com.xyoye.player.surface

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import com.xyoye.data_component.enums.VideoScreenScale
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.RenderMeasureHelper

class RenderMpvView(context: Context) : TextureView(context), InterSurfaceView {

    private val measureHelper = RenderMeasureHelper()
    private var surfaceTextureRef: SurfaceTexture? = null
    private var surface: Surface? = null
    private lateinit var videoPlayer: AbstractVideoPlayer

    private val listener = object : SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            this@RenderMpvView.surface?.release()
            this@RenderMpvView.surface = null
            surfaceTextureRef = null
            return true
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceTextureRef = surface
            this@RenderMpvView.surface = Surface(surface)
            if (this@RenderMpvView::videoPlayer.isInitialized) {
                this@RenderMpvView.surface?.let { videoPlayer.setSurface(it) }
            }
        }
    }

    init {
        surfaceTextureListener = listener
        isOpaque = false
        background = null
    }

    override fun attachPlayer(player: AbstractVideoPlayer) {
        videoPlayer = player
    }

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
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
        surface?.release()
        surface = null
        surfaceTextureRef?.release()
        surfaceTextureRef = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredSize = measureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredSize[0], measuredSize[1])
    }
}
