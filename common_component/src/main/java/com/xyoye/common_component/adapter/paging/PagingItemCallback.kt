package com.xyoye.common_component.adapter.paging

import androidx.recyclerview.widget.DiffUtil

/**
 * Created by xyoye on 2020/12/2.
 */

class PagingItemCallback<T>(
    private val mAreItemsTheSame: (T, T) -> Boolean = { o, n -> o == n },
    private val mAreContentsTheSame: (T, T) -> Boolean = { o, n -> o == n }
) : DiffUtil.ItemCallback<T>() {
    companion object {
        fun <T> getDefault(): PagingItemCallback<T> = PagingItemCallback()
    }

    override fun areItemsTheSame(
        oldItem: T & Any,
        newItem: T & Any
    ): Boolean = mAreItemsTheSame(oldItem, newItem)

    override fun areContentsTheSame(
        oldItem: T & Any,
        newItem: T & Any
    ): Boolean = mAreContentsTheSame(oldItem, newItem)
}
