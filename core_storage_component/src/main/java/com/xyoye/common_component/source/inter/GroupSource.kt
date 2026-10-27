package com.xyoye.common_component.source.inter

/**
 * Created by xyoye on 2021/11/14.
 *
 * 组资源，实现资源间切换
 */

interface GroupSource {
    fun getGroupIndex(): Int

    fun getGroupSize(): Int

    fun hasNextSource(): Boolean

    fun hasPreviousSource(): Boolean

    fun indexTitle(index: Int): String
}
