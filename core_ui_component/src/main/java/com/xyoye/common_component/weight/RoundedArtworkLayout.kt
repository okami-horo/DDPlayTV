package com.xyoye.common_component.weight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.FrameLayout

/** Clips artwork and overlays on both bitmap and hardware canvases. */
class RoundedArtworkLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : FrameLayout(context, attrs) {
        private val artworkPath = Path()

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            val radius = (background as GradientDrawable).cornerRadius
            artworkPath.rewind()
            artworkPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            val checkpoint = canvas.save()
            canvas.clipPath(artworkPath)
            super.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }
