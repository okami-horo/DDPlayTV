package com.xyoye.common_component.focus

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.extension.toResString
import com.xyoye.core_ui_component.R
import java.lang.ref.WeakReference

class DpadDialogFocusController(
    private val activity: Activity,
    private val rootProvider: () -> View?,
    private val fallbackProvider: (() -> View?)? = null,
) {
    private var hostFocusRef: WeakReference<View>? = null

    fun captureHostFocus() {
        if (!isEnabled()) return
        val focus = activity.currentFocus ?: return
        if (focus.isShown && focus.isAttachedToWindow) {
            hostFocusRef = WeakReference(focus)
        }
    }

    fun requestDialogFocus() {
        if (!isEnabled()) return
        val root = rootProvider() ?: return
        root.post {
            findDialogFocusTarget(root)?.requestFocus()
        }
    }

    fun restoreHostFocus() {
        if (!isEnabled()) return
        val hostFocus = hostFocusRef?.get()
        val restored = hostFocus != null && hostFocus.isShown && hostFocus.isAttachedToWindow && hostFocus.isFocusable && hostFocus.requestFocus()
        if (!restored) {
            fallbackProvider
                ?.invoke()
                ?.takeIf { it.isShown && it.isAttachedToWindow && it.isFocusable }
                ?.requestFocus()
        }
    }

    private fun isEnabled(): Boolean = activity.isTelevisionUiMode() && activity.window?.decorView?.isInTouchMode != true

    private fun findDialogFocusTarget(root: View): View? {
        val tag = R.string.focusable_item.toResString()
        root.findViewWithTag<View>(tag)?.let { target ->
            if (target.isShown && target.isFocusable) return target
        }
        return findFirstFocusable(root)
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.isShown && view.isFocusable) return view
        val group = view as? ViewGroup ?: return null
        group.children.forEach { child ->
            val found = findFirstFocusable(child)
            if (found != null) return found
        }
        return null
    }
}
