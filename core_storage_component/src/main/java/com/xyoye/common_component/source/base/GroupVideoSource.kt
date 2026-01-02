package com.xyoye.common_component.source.base

import com.xyoye.common_component.source.inter.GroupSource

/**
 * Created by xyoye on 2021/11/14.
 */

abstract class GroupVideoSource(
    private val index: Int,
    private val videoSources: List<*>
) : GroupSource {
    override fun getGroupIndex(): Int = index

    override fun getGroupSize(): Int = videoSources.size

    override fun hasNextSource(): Boolean = index + 1 in videoSources.indices

    override fun hasPreviousSource(): Boolean = index - 1 in videoSources.indices
}
