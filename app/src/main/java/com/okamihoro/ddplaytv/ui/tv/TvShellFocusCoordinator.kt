package com.okamihoro.ddplaytv.ui.tv

import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.focus.DpadDirection
import com.xyoye.common_component.focus.DpadFocusOwner

class TvShellFocusCoordinator(
    private val navRecyclerView: RecyclerView,
    private val contentRoot: View,
    private val selectedNavIndexProvider: () -> Int,
    private val currentFragmentProvider: () -> Fragment?,
) {
    fun requestNavFocus(): Boolean {
        if (navRecyclerView.isInTouchMode) return false
        val index = selectedNavIndexProvider().coerceAtLeast(0)
        return navRecyclerView.requestIndexChildFocus(index)
    }

    fun requestContentFocus(): Boolean {
        if (contentRoot.isInTouchMode) return false
        val owner = currentFragmentProvider() as? DpadFocusOwner ?: return false
        return owner.restoreDpadFocus() || owner.requestDefaultDpadFocus()
    }

    fun saveContentFocus() {
        (currentFragmentProvider() as? DpadFocusOwner)?.saveDpadFocus()
    }

    fun onNavKeyDown(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        return requestContentFocus()
    }

    fun onContentKeyDown(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false
        val focus = contentRoot.findFocus() ?: return false
        val next = focus.focusSearch(View.FOCUS_LEFT)
        if (next != null && isDescendantOf(next, contentRoot)) return false
        return requestNavFocus()
    }

    fun onContentBoundary(direction: DpadDirection): Boolean {
        if (direction != DpadDirection.Left) return false
        return requestNavFocus()
    }

    private fun isDescendantOf(
        view: View,
        ancestor: View,
    ): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }
}
