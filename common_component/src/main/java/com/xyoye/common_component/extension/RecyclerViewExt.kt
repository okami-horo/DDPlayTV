package com.xyoye.common_component.extension

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.R
import com.xyoye.common_component.adapter.BaseAdapter
import kotlin.math.abs

/**
 * Created by xyoye on 2020/8/17.
 */

private const val FOCUS_KEYLINE_PERCENT = 0.5f

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

    val useKeylineAlignment = !isInTouchMode
    val targetTag = R.string.focusable_item.toResString()

    fun tryRequestFocus(): Boolean {
        val target = layoutManager.findViewByPosition(index) ?: return false
        val focusView = target.findViewWithTag<View>(targetTag) ?: target.takeIf { it.isFocusable }
        if (focusView == null) {
            return false
        }

        ensureChildFullyVisible(target)

        val requested = focusView.requestFocus()
        if (requested && useKeylineAlignment) {
            alignChildToKeyline(index, layoutManager)
            layoutManager.findViewByPosition(index)?.let { ensureChildFullyVisible(it) }
        }
        return requested
    }

    if (tryRequestFocus()) {
        return true
    }

    alignPosition(index, layoutManager, useKeylineAlignment)
    post {
        tryRequestFocus()
    }
    return true
}

private fun RecyclerView.alignPosition(
    index: Int,
    layoutManager: RecyclerView.LayoutManager,
    useKeylineAlignment: Boolean,
) {
    if (!useKeylineAlignment) {
        if (layoutManager is LinearLayoutManager) {
            val offset = if (layoutManager.orientation == RecyclerView.VERTICAL) paddingTop else paddingLeft
            layoutManager.scrollToPositionWithOffset(index, offset)
        } else {
            layoutManager.scrollToPosition(index)
        }
        return
    }

    // Keyline alignment: scroll close first, then fine-tune after the item is laid out.
    layoutManager.scrollToPosition(index)
}

private fun RecyclerView.alignChildToKeyline(
    index: Int,
    layoutManager: RecyclerView.LayoutManager,
) {
    val linearLayoutManager = layoutManager as? LinearLayoutManager ?: return
    val target = linearLayoutManager.findViewByPosition(index) ?: return

    val isVertical = linearLayoutManager.orientation == RecyclerView.VERTICAL
    val parentSize = if (isVertical) height else width
    val startPadding = if (isVertical) paddingTop else paddingLeft
    val endPadding = if (isVertical) paddingBottom else paddingRight
    val childStart = if (isVertical) target.top else target.left
    val childEnd = if (isVertical) target.bottom else target.right

    val availableSpace = parentSize - startPadding - endPadding
    if (availableSpace <= 0) {
        return
    }

    val keyline = startPadding + (availableSpace * FOCUS_KEYLINE_PERCENT).toInt()
    val childCenter = (childStart + childEnd) / 2
    val delta = childCenter - keyline
    if (abs(delta) < 1) {
        return
    }
    if (isVertical) {
        scrollBy(0, delta)
    } else {
        scrollBy(delta, 0)
    }
}

private fun RecyclerView.ensureChildFullyVisible(target: View) {
    val rect = Rect()
    target.getDrawingRect(rect)
    offsetDescendantRectToMyCoords(target, rect)
    requestChildRectangleOnScreen(target, rect, true)
}
