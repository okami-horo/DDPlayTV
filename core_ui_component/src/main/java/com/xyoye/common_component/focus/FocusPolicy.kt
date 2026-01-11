package com.xyoye.common_component.focus

import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import com.xyoye.core_ui_component.R

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

    @MainThread
    fun setDescendantFocusBlocked(
        root: ViewGroup,
        blocked: Boolean
    ) {
        val originKey = R.id.focus_policy_origin_descendant_focusability
        if (root.getTag(originKey) == null) {
            root.setTag(originKey, root.descendantFocusability)
        }

        if (blocked) {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.clearFocus()
        } else {
            root.descendantFocusability =
                (root.getTag(originKey) as? Int)
                    ?: ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    @MainThread
    fun resetDescendantFocus(
        root: ViewGroup,
        restoreAsync: Boolean = true
    ) {
        val originKey = R.id.focus_policy_origin_descendant_focusability
        if (root.getTag(originKey) == null) {
            root.setTag(originKey, root.descendantFocusability)
        }

        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.clearFocus()
        if (!root.isInTouchMode) {
            root.requestFocus()
        }
        if (restoreAsync) {
            root.post { setDescendantFocusBlocked(root, blocked = false) }
        } else {
            setDescendantFocusBlocked(root, blocked = false)
        }
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

@MainThread
fun ViewGroup.setDescendantFocusBlocked(blocked: Boolean) {
    FocusPolicy.setDescendantFocusBlocked(this, blocked)
}

@MainThread
fun ViewGroup.resetDescendantFocus(restoreAsync: Boolean = true) {
    FocusPolicy.resetDescendantFocus(this, restoreAsync)
}
