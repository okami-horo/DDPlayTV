package com.xyoye.player.subtitle.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class SubtitleSurfaceOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var frameSizeListener: ((Int, Int) -> Unit)? = null

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
    }

    fun setOnFrameSizeChanged(listener: (width: Int, height: Int) -> Unit) {
        frameSizeListener = listener
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        frameSizeListener?.invoke(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }

    fun render(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            clear()
            return
        }
        if (!holder.surface.isValid) {
            return
        }
        val canvas = holder.lockCanvas() ?: return
        try {
            drawFrame(canvas, bitmap)
        } finally {
            holder.unlockCanvasAndPost(canvas)
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

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
