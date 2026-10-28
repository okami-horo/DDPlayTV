package com.okamihoro.ddplaytv.ui.tv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Called by a child list only after its layout manager allows focus to leave. */
class TvContentContainer
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : FrameLayout(context, attrs) {
        var navigationTarget: (() -> View?)? = null

        override fun focusSearch(
            focused: View,
            direction: Int
        ): View? {
            if (direction == View.FOCUS_LEFT) {
                navigationTarget?.invoke()?.let { return it }
            }
            return super.focusSearch(focused, direction)
        }
    }
