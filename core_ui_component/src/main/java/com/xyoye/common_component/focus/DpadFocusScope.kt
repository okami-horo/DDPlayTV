package com.xyoye.common_component.focus

import android.view.View
import java.lang.ref.WeakReference

class DpadFocusScope(
    private val isEnabled: () -> Boolean,
    private val defaultFocusProvider: () -> View?,
) {
    private var lastFocusedRef: WeakReference<View>? = null

    fun capture(view: View?) {
        if (!isEnabled()) return
        if (view == null) return
        if (!view.isShown || !view.isAttachedToWindow) return
        lastFocusedRef = WeakReference(view)
    }

    fun captureFrom(root: View?) {
        if (!isEnabled()) return
        capture(root?.findFocus())
    }

    fun restore(): Boolean {
        if (!isEnabled()) return false
        val lastFocused = lastFocusedRef?.get()
        if (lastFocused != null && lastFocused.isShown && lastFocused.isAttachedToWindow && lastFocused.isFocusable) {
            if (lastFocused.requestFocus()) return true
        }
        val defaultFocus = defaultFocusProvider()
        if (defaultFocus != null && defaultFocus.isShown && defaultFocus.isAttachedToWindow && defaultFocus.isFocusable) {
            return defaultFocus.requestFocus()
        }
        return false
    }

    fun clear() {
        lastFocusedRef = null
    }
}
