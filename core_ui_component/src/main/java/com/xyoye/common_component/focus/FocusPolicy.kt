package com.xyoye.common_component.focus

import android.view.View
import androidx.annotation.MainThread

object FocusPolicy {
    fun isInDpadMode(view: View): Boolean = !view.isInTouchMode

    @MainThread
    fun applyDpadFocusable(
        view: View,
        enabled: Boolean,
        inTouchMode: Boolean = view.isInTouchMode,
        clickable: Boolean? = null
    ) {
        view.isFocusable = enabled
        view.isFocusableInTouchMode = enabled && !inTouchMode
        if (clickable != null) {
            view.isClickable = clickable
        }
    }

    @MainThread
    fun requestDefaultFocus(view: View): Boolean {
        if (view.isInTouchMode) {
            return false
        }
        if (view.hasFocus()) {
            return false
        }
        return view.requestFocus()
    }
}

@MainThread
fun View.applyDpadFocusable(
    enabled: Boolean,
    inTouchMode: Boolean = isInTouchMode,
    clickable: Boolean? = null
) {
    FocusPolicy.applyDpadFocusable(
        view = this,
        enabled = enabled,
        inTouchMode = inTouchMode,
        clickable = clickable,
    )
}

@MainThread
fun View.requestDefaultFocus(): Boolean = FocusPolicy.requestDefaultFocus(this)
