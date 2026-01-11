package com.xyoye.common_component.focus

import android.view.KeyEvent
import android.view.View
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.extension.FocusTarget
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.toResString
import com.xyoye.core_ui_component.R

class RecyclerViewFocusDelegate(
    private val recyclerView: RecyclerView,
    private val uniqueKeyProvider: ((Any) -> String?)? = null
) {
    var lastFocusedIndex: Int = RecyclerView.NO_POSITION
        private set

    var pendingFocusIndex: Int = RecyclerView.NO_POSITION
        private set

    var pendingFocusUniqueKey: String? = null
        private set

    fun onResume() {
        if (lastFocusedIndex != RecyclerView.NO_POSITION) {
            pendingFocusIndex = lastFocusedIndex
        }
        setChildrenFocusable(true)
    }

    fun onPause() {
        saveCurrentFocusIndex()
        setChildrenFocusable(false)
    }

    fun installVerticalDpadKeyNavigation(
        focusTargetProvider: (RecyclerView) -> FocusTarget = { defaultFocusTarget() },
        onMenuKeyDown: (() -> Unit)? = null,
        onSettingsKeyDown: (() -> Unit)? = onMenuKeyDown
    ) {
        recyclerView.setOnKeyListener { _, keyCode, event ->
            if (event?.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            if (recyclerView.isInTouchMode) {
                return@setOnKeyListener false
            }

            val rvAdapter = recyclerView.adapter ?: return@setOnKeyListener false
            val focusedChild = recyclerView.focusedChild ?: return@setOnKeyListener false
            val currentIndex = recyclerView.getChildAdapterPosition(focusedChild)
            if (currentIndex == RecyclerView.NO_POSITION) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    onMenuKeyDown?.invoke() ?: return@setOnKeyListener false
                    true
                }

                KeyEvent.KEYCODE_SETTINGS -> {
                    onSettingsKeyDown?.invoke() ?: return@setOnKeyListener false
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN ->
                    moveFocusBy(
                        offset = 1,
                        itemCount = rvAdapter.itemCount,
                        currentIndex = currentIndex,
                        focusTarget = focusTargetProvider(recyclerView),
                    )

                KeyEvent.KEYCODE_DPAD_UP ->
                    moveFocusBy(
                        offset = -1,
                        itemCount = rvAdapter.itemCount,
                        currentIndex = currentIndex,
                        focusTarget = focusTargetProvider(recyclerView),
                    )

                else -> false
            }
        }
    }

    fun setChildrenFocusable(
        focusable: Boolean,
        target: FocusTarget = defaultFocusTarget()
    ) {
        val inTouchMode = recyclerView.isInTouchMode
        val defaultTag = R.string.focusable_item.toResString()

        recyclerView.children.forEach { child ->
            val focusView =
                when (target) {
                    is FocusTarget.Tag -> child.findViewWithTag<View>(target.tag)
                    is FocusTarget.ViewId -> child.findViewById(target.viewId)
                    FocusTarget.ItemRoot -> child.takeIf { it.isFocusable }
                }
                    ?: child.findViewWithTag<View>(defaultTag)
                    ?: child

            focusView.isFocusable = focusable
            focusView.isFocusableInTouchMode = focusable && !inTouchMode
        }
    }

    fun setPendingFocus(
        index: Int = RecyclerView.NO_POSITION,
        uniqueKey: String? = null
    ) {
        pendingFocusIndex = index
        pendingFocusUniqueKey = uniqueKey
    }

    fun clearPendingFocus() {
        pendingFocusIndex = RecyclerView.NO_POSITION
        pendingFocusUniqueKey = null
    }

    fun saveCurrentFocusIndex() {
        val focusedChild = recyclerView.focusedChild ?: return
        val index = recyclerView.getChildAdapterPosition(focusedChild)
        if (index != RecyclerView.NO_POSITION) {
            lastFocusedIndex = index
            pendingFocusIndex = index
        }
    }

    fun resolvePendingFocusIndex(
        items: List<Any>,
        itemCount: Int
    ): Int {
        if (itemCount <= 0) {
            return 0
        }

        val pendingKey = pendingFocusUniqueKey
        if (!pendingKey.isNullOrEmpty() && uniqueKeyProvider != null) {
            val resolvedIndex =
                items.indexOfFirst { item ->
                    uniqueKeyProvider.invoke(item) == pendingKey
                }
            if (resolvedIndex in 0 until itemCount) {
                return resolvedIndex
            }
        }

        if (pendingFocusIndex != RecyclerView.NO_POSITION) {
            return pendingFocusIndex.coerceIn(0, itemCount - 1)
        }

        return 0
    }

    fun captureFocusedItem(): Pair<Int, String?> {
        val focusedChild = recyclerView.focusedChild ?: return RecyclerView.NO_POSITION to null
        val focusedIndex = recyclerView.getChildAdapterPosition(focusedChild)
        if (focusedIndex == RecyclerView.NO_POSITION) {
            return focusedIndex to null
        }

        val provider = uniqueKeyProvider ?: return focusedIndex to null
        val adapter = recyclerView.adapter as? BaseAdapter ?: return focusedIndex to null
        val item = adapter.items.getOrNull(focusedIndex) ?: return focusedIndex to null
        return focusedIndex to provider.invoke(item)
    }

    fun requestFocus(
        reversed: Boolean = false,
        target: FocusTarget = defaultFocusTarget()
    ): Boolean {
        if (recyclerView.isInTouchMode) {
            return false
        }
        val adapter = recyclerView.adapter ?: return false
        if (adapter.itemCount <= 0) {
            return false
        }

        val hasPending = pendingFocusIndex != RecyclerView.NO_POSITION && !reversed
        val desiredIndex =
            when {
                hasPending -> pendingFocusIndex
                reversed -> adapter.itemCount - 1
                else -> 0
            }

        val targetIndex = desiredIndex.coerceIn(0, adapter.itemCount - 1)
        pendingFocusIndex = RecyclerView.NO_POSITION
        pendingFocusUniqueKey = null
        lastFocusedIndex = targetIndex
        return recyclerView.requestIndexChildFocus(targetIndex, target)
    }

    private fun defaultFocusTarget(): FocusTarget = FocusTarget.Tag(R.string.focusable_item.toResString())

    private fun moveFocusBy(
        offset: Int,
        itemCount: Int,
        currentIndex: Int,
        focusTarget: FocusTarget
    ): Boolean {
        if (itemCount <= 0) {
            return false
        }

        val nextIndex = currentIndex + offset
        if (nextIndex !in 0 until itemCount) {
            return false
        }

        val moved = recyclerView.requestIndexChildFocus(nextIndex, focusTarget)
        if (moved) {
            lastFocusedIndex = nextIndex
            pendingFocusIndex = RecyclerView.NO_POSITION
            pendingFocusUniqueKey = null
        }
        return moved
    }
}
