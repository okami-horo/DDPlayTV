package com.xyoye.common_component.focus

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.extension.FocusTarget
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.toResString
import com.xyoye.core_ui_component.R

enum class DpadRecyclerOrientation {
    Vertical,
    Horizontal,
    Grid,
}

data class DpadRecyclerFocusState(
    val index: Int = RecyclerView.NO_POSITION,
    val uniqueKey: String? = null,
)

class DpadRecyclerFocusDelegate(
    private val recyclerView: RecyclerView,
    private val orientation: DpadRecyclerOrientation,
    private val spanCountProvider: (() -> Int)? = null,
    private val uniqueKeyProvider: ((Any) -> String?)? = null,
    private val focusTargetProvider: (RecyclerView) -> FocusTarget = { defaultFocusTarget() },
    private val boundaryHandler: ((DpadDirection) -> Boolean)? = null,
    private val fallbackFocusProvider: (() -> View?)? = null,
    private val extraKeyHandler: ((Int, KeyEvent) -> Boolean)? = null,
) {
    private var lastFocusState = DpadRecyclerFocusState()
    private var pendingFocusState = DpadRecyclerFocusState()

    val pendingFocusIndex: Int
        get() = pendingFocusState.index

    val pendingFocusUniqueKey: String?
        get() = pendingFocusState.uniqueKey

    fun install() {
        recyclerView.setOnKeyListener { _, keyCode, event ->
            if (event?.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (recyclerView.isInTouchMode) return@setOnKeyListener false
            val direction = keyCode.toDpadDirectionOrNull()
            if (direction != null) {
                return@setOnKeyListener moveFocus(direction)
            }
            extraKeyHandler?.invoke(keyCode, event) == true
        }
    }

    fun onResume() {
        if (lastFocusState.index != RecyclerView.NO_POSITION || lastFocusState.uniqueKey != null) {
            pendingFocusState = lastFocusState
        }
    }

    fun onPause() {
        saveFocus()
    }

    fun saveFocus() {
        lastFocusState = captureFocus()
        if (lastFocusState.index != RecyclerView.NO_POSITION || lastFocusState.uniqueKey != null) {
            pendingFocusState = lastFocusState
        }
    }

    fun captureFocus(): DpadRecyclerFocusState {
        val focusedChild = recyclerView.focusedChild ?: return DpadRecyclerFocusState()
        val index = recyclerView.getChildAdapterPosition(focusedChild)
        if (index == RecyclerView.NO_POSITION) return DpadRecyclerFocusState()
        return DpadRecyclerFocusState(index = index, uniqueKey = uniqueKeyAt(index))
    }

    fun setPendingFocus(index: Int = RecyclerView.NO_POSITION, uniqueKey: String? = null) {
        pendingFocusState = DpadRecyclerFocusState(index, uniqueKey)
    }

    fun clearPendingFocus() {
        pendingFocusState = DpadRecyclerFocusState()
    }

    fun requestDefaultFocus(reversed: Boolean = false): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return fallbackFocus()
        if (count <= 0) return fallbackFocus()
        val index = if (reversed) count - 1 else 0
        return requestFocusAt(index) || fallbackFocus()
    }

    fun restoreFocus(reversed: Boolean = false): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return fallbackFocus()
        if (count <= 0) return fallbackFocus()
        val desired = resolveIndex(emptyList(), count, pendingFocusState, reversed)
        clearPendingFocus()
        return requestFocusAt(desired) || fallbackFocus()
    }

    fun restoreAfterDataChanged(
        items: List<Any>,
        state: DpadRecyclerFocusState = pendingFocusState,
    ): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return fallbackFocus()
        if (count <= 0) return fallbackFocus()
        val index = resolveIndex(items, count, state, reversed = false)
        pendingFocusState = DpadRecyclerFocusState(index, state.uniqueKey)
        return recyclerView.postRequestFocusAt(index)
    }

    fun requestFocus(reversed: Boolean = false): Boolean = restoreFocus(reversed)

    fun resolvePendingFocusIndex(
        items: List<Any>,
        itemCount: Int,
    ): Int = resolveIndex(items, itemCount, pendingFocusState, reversed = false)

    fun captureFocusedItem(): Pair<Int, String?> {
        val state = captureFocus()
        return state.index to state.uniqueKey
    }

    private fun moveFocus(direction: DpadDirection): Boolean {
        val count = recyclerView.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val current = currentIndex(count)
        val target = targetIndex(current, count, direction)
        if (target == RecyclerView.NO_POSITION) {
            return boundaryHandler?.invoke(direction) == true
        }
        return requestFocusAt(target)
    }

    private fun currentIndex(count: Int): Int {
        val focusedChild = recyclerView.focusedChild
        val focusedIndex = if (focusedChild != null) recyclerView.getChildAdapterPosition(focusedChild) else RecyclerView.NO_POSITION
        if (focusedIndex in 0 until count) return focusedIndex
        if (lastFocusState.index in 0 until count) return lastFocusState.index
        if (pendingFocusState.index in 0 until count) return pendingFocusState.index
        return 0
    }

    private fun targetIndex(
        current: Int,
        count: Int,
        direction: DpadDirection,
    ): Int {
        val span = spanCount().coerceAtLeast(1)
        val target =
            when (orientation) {
                DpadRecyclerOrientation.Vertical ->
                    when (direction) {
                        DpadDirection.Up -> current - 1
                        DpadDirection.Down -> current + 1
                        DpadDirection.Left,
                        DpadDirection.Right -> return RecyclerView.NO_POSITION
                    }
                DpadRecyclerOrientation.Horizontal ->
                    when (direction) {
                        DpadDirection.Left -> current - 1
                        DpadDirection.Right -> current + 1
                        DpadDirection.Up,
                        DpadDirection.Down -> return RecyclerView.NO_POSITION
                    }
                DpadRecyclerOrientation.Grid ->
                    when (direction) {
                        DpadDirection.Left -> if (current % span == 0) RecyclerView.NO_POSITION else current - 1
                        DpadDirection.Right -> if (current % span == span - 1) RecyclerView.NO_POSITION else current + 1
                        DpadDirection.Up -> current - span
                        DpadDirection.Down -> current + span
                    }
            }
        return if (target in 0 until count) target else RecyclerView.NO_POSITION
    }

    private fun spanCount(): Int {
        spanCountProvider?.invoke()?.let { return it }
        return (recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 1
    }

    private fun resolveIndex(
        items: List<Any>,
        count: Int,
        state: DpadRecyclerFocusState,
        reversed: Boolean,
    ): Int {
        val key = state.uniqueKey
        if (!key.isNullOrEmpty() && uniqueKeyProvider != null && items.isNotEmpty()) {
            val keyIndex = items.indexOfFirst { uniqueKeyProvider.invoke(it) == key }
            if (keyIndex in 0 until count) return keyIndex
        }
        if (state.index in 0 until count) return state.index
        return if (reversed) count - 1 else 0
    }

    private fun requestFocusAt(index: Int): Boolean {
        val moved = recyclerView.requestIndexChildFocus(index, focusTargetProvider(recyclerView))
        if (moved) {
            lastFocusState = DpadRecyclerFocusState(index, uniqueKeyAt(index))
            clearPendingFocus()
        }
        return moved
    }

    private fun RecyclerView.postRequestFocusAt(index: Int): Boolean {
        post { requestFocusAt(index) }
        return true
    }

    private fun fallbackFocus(): Boolean {
        val fallback = fallbackFocusProvider?.invoke() ?: return false
        if (!fallback.isShown || !fallback.isAttachedToWindow || !fallback.isFocusable) return false
        return fallback.requestFocus()
    }

    private fun uniqueKeyAt(index: Int): String? {
        val provider = uniqueKeyProvider ?: return null
        val adapter = recyclerView.adapter as? BaseAdapter ?: return null
        val item = adapter.items.getOrNull(index) ?: return null
        return provider.invoke(item)
    }

    companion object {
        fun defaultFocusTarget(): FocusTarget = FocusTarget.Tag(R.string.focusable_item.toResString())
    }
}
