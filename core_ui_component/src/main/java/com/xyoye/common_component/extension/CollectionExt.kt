package com.xyoye.common_component.extension

import com.xyoye.common_component.config.AppConfig

/**
 * Created by xyoye on 2021/3/2.
 */

/**
 * 过滤以.开头的文件
 */
inline fun <T> Iterable<T>.filterHiddenFile(predicate: (T) -> String): List<T> =
    filterTo(ArrayList()) {
        AppConfig.isShowHiddenFile() || predicate.invoke(it).startsWith(".").not()
    }

/**
 * 过滤以.开头的文件
 */
inline fun <T> Sequence<T>.filterHiddenFile(predicate: (T) -> String): MutableCollection<T> =
    filterTo(ArrayList()) {
        AppConfig.isShowHiddenFile() || predicate.invoke(it).startsWith(".").not()
    }

inline fun <T> mapByLength(
    length: Int,
    action: (Int) -> T
): List<T> {
    val list = mutableListOf<T>()
    for (index in 0 until length) {
        list.add(action(index))
    }
    return list
}
