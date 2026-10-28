package com.xyoye.common_component.utils.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.rubensousa.dpadrecyclerview.spacing.DpadGridSpacingDecoration

class ItemDecorationDrawable : ItemDecoration {
    private var leftRight: Int = 0
    private var topBottom: Int = 0
    private var mDivider: Drawable?
    private val dpadSpacing by lazy {
        DpadGridSpacingDecoration(topBottom, topBottom, topBottom, leftRight)
    }

    constructor(spacePx: Int) {
        leftRight = spacePx
        topBottom = spacePx
        mDivider = ColorDrawable(Color.WHITE)
    }

    constructor(leftRight: Int, topBottom: Int) {
        this.leftRight = leftRight
        this.topBottom = topBottom
        mDivider = ColorDrawable(Color.WHITE)
    }

    constructor(leftRight: Int, topBottom: Int, mColor: Int) {
        this.leftRight = leftRight
        this.topBottom = topBottom
        mDivider = ColorDrawable(mColor)
    }

    override fun onDraw(
        c: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        val divider = mDivider ?: return
        if (layoutManager.childCount == 0) return

        val spanCount = layoutManager.spanCount
        val lookup = layoutManager.spanSizeLookup
        if (layoutManager.orientation == GridLayoutManager.VERTICAL) {
            drawVertical(c, parent, layoutManager, lookup, divider, spanCount)
        } else {
            drawHorizontal(c, parent, layoutManager, lookup, divider, spanCount)
        }
    }

    private fun drawVertical(
        canvas: Canvas,
        parent: RecyclerView,
        layoutManager: GridLayoutManager,
        lookup: GridLayoutManager.SpanSizeLookup,
        divider: Drawable,
        spanCount: Int
    ) {
        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val child = parent.getChildAt(i)
            val centerLeft =
                (
                    (
                        layoutManager.getLeftDecorationWidth(child) +
                            layoutManager.getRightDecorationWidth(child)
                    ).toFloat() *
                        spanCount / (spanCount + 1) + 1 - leftRight
                ) / 2
            val centerTop = (layoutManager.getBottomDecorationHeight(child) + 1 - topBottom) / 2f
            val position = parent.getChildAdapterPosition(child)
            val spanSize = lookup.getSpanSize(position)
            val spanIndex = lookup.getSpanIndex(position, layoutManager.spanCount)
            val isFirst = layoutManager.spanSizeLookup.getSpanGroupIndex(position, spanCount) == 0

            if (!isFirst && spanIndex == 0) {
                val left = layoutManager.getLeftDecorationWidth(child)
                val right = parent.width - layoutManager.getLeftDecorationWidth(child)
                val top = (child.top - centerTop).toInt() - topBottom
                val bottom = top + topBottom
                divider.setBounds(left, top, right, bottom)
                divider.draw(canvas)
            }

            val isRight = spanIndex + spanSize == spanCount
            if (!isRight) {
                val left = (child.right + centerLeft).toInt()
                val right = left + leftRight
                val top = if (isFirst) child.top else child.top - centerTop.toInt()
                val bottom = (child.bottom + centerTop).toInt()
                divider.setBounds(left, top, right, bottom)
                divider.draw(canvas)
            }
        }
    }

    private fun drawHorizontal(
        canvas: Canvas,
        parent: RecyclerView,
        layoutManager: GridLayoutManager,
        lookup: GridLayoutManager.SpanSizeLookup,
        divider: Drawable,
        spanCount: Int
    ) {
        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val child = parent.getChildAt(i)
            val centerLeft = (layoutManager.getRightDecorationWidth(child) + 1 - leftRight) / 2f
            val centerTop =
                (
                    (
                        layoutManager.getTopDecorationHeight(child) +
                            layoutManager.getBottomDecorationHeight(child)
                    ).toFloat() *
                        spanCount / (spanCount + 1) - topBottom
                ) / 2
            val position = parent.getChildAdapterPosition(child)
            val spanSize = lookup.getSpanSize(position)
            val spanIndex = lookup.getSpanIndex(position, layoutManager.spanCount)
            val isFirst = layoutManager.spanSizeLookup.getSpanGroupIndex(position, spanCount) == 0

            if (!isFirst && spanIndex == 0) {
                val left = (child.left - centerLeft).toInt() - leftRight
                val right = left + leftRight
                val top = layoutManager.getRightDecorationWidth(child)
                val bottom = parent.height - layoutManager.getTopDecorationHeight(child)
                divider.setBounds(left, top, right, bottom)
                divider.draw(canvas)
            }

            val isRight = spanIndex + spanSize == spanCount
            if (!isRight) {
                val left = if (isFirst) child.left else child.left - centerLeft.toInt()
                val right = (child.right + centerTop).toInt()
                val top = (child.bottom + centerLeft).toInt()
                val bottom = top + leftRight
                divider.setBounds(left, top, right, bottom)
                divider.draw(canvas)
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (parent is DpadRecyclerView) {
            dpadSpacing.getItemOffsets(outRect, view, parent, state)
            return
        }
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        val lp =
            view.layoutParams as GridLayoutManager.LayoutParams
        val childPosition = parent.getChildAdapterPosition(view)
        val spanCount = layoutManager.spanCount
        if (layoutManager.orientation == GridLayoutManager.VERTICAL) { // 判断是否在第一排
            if (layoutManager.spanSizeLookup.getSpanGroupIndex(
                    childPosition,
                    spanCount,
                ) == 0
            ) { // 第一排的需要上面
                outRect.top = topBottom
            }
            outRect.bottom = topBottom
            // 这里忽略和合并项的问题，只考虑占满和单一的问题
            if (lp.spanSize == spanCount) { // 占满
                outRect.left = leftRight
                outRect.right = leftRight
            } else {
                outRect.left =
                    ((spanCount - lp.spanIndex).toFloat() / spanCount * leftRight).toInt()
                outRect.right =
                    (leftRight.toFloat() * (spanCount + 1) / spanCount - outRect.left).toInt()
            }
        } else {
            if (layoutManager.spanSizeLookup.getSpanGroupIndex(
                    childPosition,
                    spanCount,
                ) == 0
            ) { // 第一排的需要left
                outRect.left = leftRight
            }
            outRect.right = leftRight
            // 这里忽略和合并项的问题，只考虑占满和单一的问题
            if (lp.spanSize == spanCount) { // 占满
                outRect.top = topBottom
                outRect.bottom = topBottom
            } else {
                outRect.top =
                    ((spanCount - lp.spanIndex).toFloat() / spanCount * topBottom).toInt()
                outRect.bottom =
                    (topBottom.toFloat() * (spanCount + 1) / spanCount - outRect.top).toInt()
            }
        }
    }
}
