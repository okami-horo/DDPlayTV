package com.xyoye.common_component.extension

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.rubensousa.dpadrecyclerview.DpadSpanSizeLookup
import com.xyoye.common_component.adapter.BaseAdapter

/**
 * Created by xyoye on 2020/8/17.
 */

fun RecyclerView.vertical(reverse: Boolean = false): RecyclerView.LayoutManager =
    if (this is DpadRecyclerView) {
        setOrientation(RecyclerView.VERTICAL)
        setReverseLayout(reverse)
        setSpanCount(1)
        requireNotNull(layoutManager)
    } else {
        LinearLayoutManager(context, LinearLayoutManager.VERTICAL, reverse)
    }

fun RecyclerView.horizontal(reverse: Boolean = false): RecyclerView.LayoutManager =
    if (this is DpadRecyclerView) {
        setOrientation(RecyclerView.HORIZONTAL)
        setReverseLayout(reverse)
        setSpanCount(1)
        requireNotNull(layoutManager)
    } else {
        LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, reverse)
    }

fun RecyclerView.grid(
    spanCount: Int,
    spanSize: (Int) -> Int = { 1 }
): RecyclerView.LayoutManager {
    if (this is DpadRecyclerView) {
        setOrientation(RecyclerView.VERTICAL)
        setSpanCount(spanCount)
        setSpanSizeLookup(
            object : DpadSpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = spanSize(position)
            }
        )
        return requireNotNull(layoutManager)
    }
    return GridLayoutManager(context, spanCount).also {
        it.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = spanSize(position)
            }
    }
}

fun RecyclerView.gridEmpty(spanCount: Int): RecyclerView.LayoutManager =
    grid(spanCount) { position ->
        if (position >= 0 && adapter?.getItemViewType(position) == BaseAdapter.VIEW_TYPE_EMPTY) spanCount else 1
    }

fun RecyclerView.setData(items: List<Any>) {
    (adapter as? BaseAdapter)?.setData(items)
}
