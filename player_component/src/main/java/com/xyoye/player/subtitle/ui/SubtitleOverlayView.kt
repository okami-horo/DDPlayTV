package com.xyoye.player.subtitle.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.xyoye.common_component.utils.DDLog
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
    private var lastRenderAtNs = 0L
    private var throttleUntilNs = 0L
    private var maxObservedBufferBytes = 0L
    private var renderCount = 0
    private var lastTraceAtNs = 0L

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
    fun render(bitmap: Bitmap?, verticalOffsetPx: Int = 0) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            clear()
            return
        }
        if (!isAvailable || surfaceTexture == null) {
            DDLog.w("LIBASS-Render", "TextureView surface unavailable; drop frame")
            return
        }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs < throttleUntilNs) {
            return
        }
        assertMemoryBound(bitmap)
        val canvas = lockCanvas() ?: return
        val drawStart = SystemClock.elapsedRealtimeNanos()
        val previousRenderNs = lastRenderAtNs
        try {
            drawFrame(canvas, bitmap, verticalOffsetPx)
        } finally {
            unlockCanvasAndPost(canvas)
        }
        val drawDurationNs = SystemClock.elapsedRealtimeNanos() - drawStart
        renderCount++
        lastRenderAtNs = nowNs
        traceCadence(nowNs, drawDurationNs, previousRenderNs)
        throttleIfNeeded(drawDurationNs, nowNs)
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, verticalOffsetPx: Int) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val offset = verticalOffsetPx.toFloat()
        if (offset != 0f) {
            canvas.translate(0f, offset)
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

    fun clear() {
        if (!isAvailable || surfaceTexture == null) {
            return
        }
        val canvas = lockCanvas() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            unlockCanvasAndPost(canvas)
        }
    }

    private fun assertMemoryBound(bitmap: Bitmap) {
        val current = bitmap.allocationByteCount.toLong()
        if (current > maxObservedBufferBytes) {
            maxObservedBufferBytes = current
        }
        val allowed = bitmap.width.toLong() * bitmap.height.toLong() * 4L * 2L
        if (current > allowed) {
            DDLog.w(
                "LIBASS-Perf",
                "subtitle bitmap exceeds buffer budget current=${current / 1024}KB allowed=${allowed / 1024}KB"
            )
        }
    }

    private fun traceCadence(nowNs: Long, drawDurationNs: Long, previousRenderNs: Long) {
        val sinceLastMs = if (previousRenderNs == 0L) 0.0 else (nowNs - previousRenderNs) / 1_000_000.0
        if (renderCount % 30 == 0) {
            val drawMs = drawDurationNs / 1_000_000.0
            val traceWindowMs = if (lastTraceAtNs == 0L) 0.0 else (nowNs - lastTraceAtNs) / 1_000_000.0
            lastTraceAtNs = nowNs
            DDLog.d(
                "LIBASS-Render",
                "cadence frame=$renderCount interval=${"%.2f".format(sinceLastMs)}ms draw=${"%.2f".format(drawMs)}ms window=${"%.2f".format(traceWindowMs)}ms"
            )
        }
    }

    private fun throttleIfNeeded(drawDurationNs: Long, nowNs: Long) {
        val frameBudgetNs = 16_000_000L
        throttleUntilNs = when {
            drawDurationNs > frameBudgetNs * 2 -> nowNs + frameBudgetNs
            drawDurationNs > frameBudgetNs -> nowNs + frameBudgetNs / 2
            else -> nowNs
        }
    }
}
