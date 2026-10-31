package com.okamihoro.ddplaytv.ui.tv

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.widget.FrameLayout

/** Preserve navigation within the content before handing its upper edge to the shell. */
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
            if (direction == View.FOCUS_UP) {
                FocusFinder.getInstance().findNextFocus(this, focused, direction)?.let { return it }
                navigationTarget?.invoke()?.let { return it }
            }
            return super.focusSearch(focused, direction)
        }
    }
