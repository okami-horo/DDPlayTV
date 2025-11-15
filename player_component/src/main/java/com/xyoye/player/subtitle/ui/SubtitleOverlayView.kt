package com.xyoye.player.subtitle.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.TextureView

class SubtitleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var frameSizeListener: ((Int, Int) -> Unit)? = null

    init {
        isOpaque = false
    }

    fun setOnFrameSizeChanged(listener: (width: Int, height: Int) -> Unit) {
        frameSizeListener = listener
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

    fun clear() {
        val canvas = lockCanvas() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            unlockCanvasAndPost(canvas)
        }
    }
}
