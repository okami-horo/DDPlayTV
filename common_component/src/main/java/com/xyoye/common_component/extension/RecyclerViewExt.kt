package com.xyoye.common_component.extension

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.R
import com.xyoye.common_component.adapter.BaseAdapter

/**
 * Created by xyoye on 2020/8/17.
 */

fun RecyclerView.vertical(reverse: Boolean = false): LinearLayoutManager =
    LinearLayoutManager(context, LinearLayoutManager.VERTICAL, reverse)

fun RecyclerView.horizontal(reverse: Boolean = false): LinearLayoutManager =
    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, reverse)

fun RecyclerView.grid(spanCount: Int): GridLayoutManager = GridLayoutManager(context, spanCount)

fun RecyclerView.gridEmpty(spanCount: Int): GridLayoutManager {
    return GridLayoutManager(context, spanCount).also {
        it.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (position == RecyclerView.NO_POSITION) {
                        return 1
                    }
                    val viewType = adapter?.getItemViewType(position)
                    if (viewType != BaseAdapter.VIEW_TYPE_EMPTY) {
                        return 1
                    }
                    return spanCount
                }
            }
    }
}

fun RecyclerView.setData(items: List<Any>) {
    (adapter as? BaseAdapter)?.setData(items)
}

fun RecyclerView.requestIndexChildFocus(index: Int): Boolean {
    val layoutManager = layoutManager ?: return false
    val adapter = adapter ?: return false
    if (index < 0 || index >= adapter.itemCount) {
        return false
    }

    val targetTag = R.string.focusable_item.toResString()

    fun tryRequestFocus(): Boolean {
        val target = layoutManager.findViewByPosition(index) ?: return false
        ensureChildFullyVisible(target)
        val focusView = target.findViewWithTag<View>(targetTag) ?: target.takeIf { it.isFocusable }
        return focusView?.requestFocus() == true
    }

    if (tryRequestFocus()) {
        return true
    }

    alignPosition(index, layoutManager)
    post {
        tryRequestFocus()
    }
    return true
}

private fun RecyclerView.alignPosition(
    index: Int,
    layoutManager: RecyclerView.LayoutManager
) {
    if (layoutManager is LinearLayoutManager) {
        val offset = if (layoutManager.orientation == RecyclerView.VERTICAL) paddingTop else paddingLeft
        layoutManager.scrollToPositionWithOffset(index, offset)
    } else {
        layoutManager.scrollToPosition(index)
    }
}

private fun RecyclerView.ensureChildFullyVisible(target: View) {
    val rect = Rect()
    target.getDrawingRect(rect)
    offsetDescendantRectToMyCoords(target, rect)
    requestChildRectangleOnScreen(target, rect, true)
}
