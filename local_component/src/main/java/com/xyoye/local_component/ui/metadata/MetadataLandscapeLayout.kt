package com.xyoye.local_component.ui.metadata

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView

/** Keeps a focused recent-addition card inside the library viewport at larger font sizes. */
class MetadataLandscapeLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : LinearLayout(context, attrs) {
        internal var viewport: RecyclerView? = null
        private val decorations = Rect()
        private val viewportSizeListener =
            OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) requestLayout()
            }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewport = parent as? RecyclerView
            viewport?.addOnLayoutChangeListener(viewportSizeListener)
        }

        override fun onDetachedFromWindow() {
            viewport?.removeOnLayoutChangeListener(viewportSizeListener)
            super.onDetachedFromWindow()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            val list = viewport ?: parent as? RecyclerView
            val viewportHeight = list?.layoutManager?.height ?: 0
            if (list == null || viewportHeight == 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            list.layoutManager?.calculateItemDecorationsForChild(this, decorations)
            val margins = layoutParams as RecyclerView.LayoutParams
            val availableHeight =
                viewportHeight - list.paddingTop - list.paddingBottom -
                    margins.topMargin - margins.bottomMargin - decorations.top - decorations.bottom
            // The image has weight; LinearLayout shrinks it before clipping the text or focus border.
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(availableHeight.coerceAtLeast(0), MeasureSpec.AT_MOST))
        }
    }
