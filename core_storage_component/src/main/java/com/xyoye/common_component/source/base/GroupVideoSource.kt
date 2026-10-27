package com.xyoye.common_component.source.base

import com.xyoye.common_component.source.inter.GroupSource

/**
 * Created by xyoye on 2021/11/14.
 */

abstract class GroupVideoSource(
    private val index: Int,
    videoSources: List<*>
) : GroupSource {
    private val groupSize = videoSources.size

    override fun getGroupIndex(): Int = index

    override fun getGroupSize(): Int = groupSize

    override fun hasNextSource(): Boolean = index + 1 in 0 until groupSize

    override fun hasPreviousSource(): Boolean = index - 1 in 0 until groupSize
}
