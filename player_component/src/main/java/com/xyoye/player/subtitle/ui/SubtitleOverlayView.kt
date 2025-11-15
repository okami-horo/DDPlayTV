package com.xyoye.player.subtitle.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.xyoye.player.DanDanVideoPlayer

class SubtitleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var frameSizeListener: ((Int, Int) -> Unit)? = null
    private var playerView: DanDanVideoPlayer? = null
    private var anchorView: View? = null
    private val playerLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        alignToRenderView()
    }
    private val anchorLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        alignToRenderView()
    }

    init {
        isOpaque = false
    }

    fun bindPlayerView(player: DanDanVideoPlayer) {
        if (playerView !== player) {
            playerView?.removeOnLayoutChangeListener(playerLayoutListener)
            playerView = player
            player.addOnLayoutChangeListener(playerLayoutListener)
        }
        refreshAnchor()
        post { alignToRenderView() }
    }

    fun setOnFrameSizeChanged(listener: (width: Int, height: Int) -> Unit) {
        frameSizeListener = listener
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        playerView?.removeOnLayoutChangeListener(playerLayoutListener)
        playerView?.addOnLayoutChangeListener(playerLayoutListener)
        anchorView?.removeOnLayoutChangeListener(anchorLayoutListener)
        anchorView?.addOnLayoutChangeListener(anchorLayoutListener)
        post { alignToRenderView() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playerView?.removeOnLayoutChangeListener(playerLayoutListener)
        anchorView?.removeOnLayoutChangeListener(anchorLayoutListener)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameSizeListener?.invoke(w, h)
    }

    @Synchronized
    fun render(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            clear()
            return
        }
        val canvas = lockCanvas() ?: return
        try {
            drawFrame(canvas, bitmap)
        } finally {
            unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun refreshAnchor() {
        val candidate = playerView?.getRenderView()?.getView()
        if (candidate === anchorView) {
            return
        }
        anchorView?.removeOnLayoutChangeListener(anchorLayoutListener)
        anchorView = candidate
        anchorView?.addOnLayoutChangeListener(anchorLayoutListener)
    }

    private fun alignToRenderView() {
        refreshAnchor()
        val anchor = anchorView ?: return
        val width = anchor.width
        val height = anchor.height
        if (width <= 0 || height <= 0) {
            return
        }
        val layoutParams = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        val targetGravity = Gravity.START or Gravity.TOP
        var changed = false
        if (layoutParams.width != width) {
            layoutParams.width = width
            changed = true
        }
        if (layoutParams.height != height) {
            layoutParams.height = height
            changed = true
        }
        if (layoutParams.leftMargin != anchor.left) {
            layoutParams.leftMargin = anchor.left
            changed = true
        }
        if (layoutParams.topMargin != anchor.top) {
            layoutParams.topMargin = anchor.top
            changed = true
        }
        if (layoutParams.rightMargin != 0) {
            layoutParams.rightMargin = 0
            changed = true
        }
        if (layoutParams.bottomMargin != 0) {
            layoutParams.bottomMargin = 0
            changed = true
        }
        if (layoutParams.gravity != targetGravity) {
            layoutParams.gravity = targetGravity
            changed = true
        }
        if (changed) {
            setLayoutParams(layoutParams)
        }
    }

    fun clear() {
        val canvas = lockCanvas() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            unlockCanvasAndPost(canvas)
        }
    }
}
