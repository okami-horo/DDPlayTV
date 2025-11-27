package com.xyoye.player.subtitle.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.xyoye.common_component.utils.DDLog
import com.xyoye.player.DanDanVideoPlayer

class SubtitleSurfaceOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var frameSizeListener: ((Int, Int) -> Unit)? = null
    private var surfaceStateListener: SurfaceStateListener? = null
    private var playerView: DanDanVideoPlayer? = null
    private var anchorView: View? = null
    private val playerLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        alignToRenderView()
    }
    private val anchorLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        alignToRenderView()
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
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

    fun setSurfaceStateListener(listener: SurfaceStateListener?) {
        surfaceStateListener = listener
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceStateListener?.onSurfaceCreated(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        frameSizeListener?.invoke(width, height)
        surfaceStateListener?.onSurfaceChanged(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceStateListener?.onSurfaceDestroyed()
    }

    fun render(bitmap: Bitmap?, verticalOffsetPx: Int = 0) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            clear()
            return
        }
        if (!holder.surface.isValid) {
            DDLog.w("LIBASS-Render", "SurfaceView invalid; drop frame")
            return
        }
        val canvas = holder.lockCanvas() ?: return
        val drawStart = SystemClock.elapsedRealtimeNanos()
        try {
            drawFrame(canvas, bitmap, verticalOffsetPx)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        val drawDurationNs = SystemClock.elapsedRealtimeNanos() - drawStart
        if (drawDurationNs > 32_000_000L) {
            DDLog.w(
                "LIBASS-Perf",
                "Surface subtitle draw slow=${drawDurationNs / 1_000_000.0}ms"
            )
        }
    }

    fun clear() {
        if (!holder.surface.isValid) {
            return
        }
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, verticalOffsetPx: Int) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (verticalOffsetPx != 0) {
            canvas.translate(0f, verticalOffsetPx.toFloat())
        }
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
            frameSizeListener?.invoke(layoutParams.width, layoutParams.height)
        }
    }

    fun interface SurfaceStateListener {
        fun onSurfaceDestroyed()

        fun onSurfaceCreated(surface: Surface?, width: Int, height: Int) {}

        fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {}
    }
}
