package com.xyoye.common_component.weight.dialog

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Keeps the dialog header and actions visible while its content scrolls. */
class CinemaDialogLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : LinearLayout(context, attrs) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            val viewportHeight = resources.displayMetrics.heightPixels
            val inset = (48 * resources.displayMetrics.density).toInt()
            val limit = (viewportHeight - inset).coerceAtLeast(0)
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val boundedHeight = if (mode == MeasureSpec.UNSPECIFIED) limit else minOf(limit, size)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(boundedHeight, if (mode == MeasureSpec.EXACTLY) mode else MeasureSpec.AT_MOST)
            )
        }
    }
