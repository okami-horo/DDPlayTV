package com.xyoye.common_component.utils

import android.content.Context
import android.graphics.Point
import android.view.MotionEvent
import android.view.WindowManager

/**
 * Created by xyoye on 2020/11/2.
 */

/**
 * 是否处于屏幕边缘
 */
fun Context.isScreenEdge(e: MotionEvent?): Boolean {
    if (e == null) {
        return false
    }
    val edgeSize = dp2px(40f)
    return e.rawX < edgeSize ||
        e.rawX > getScreenWidth() - edgeSize ||
        e.rawY < edgeSize ||
        e.rawY > getScreenHeight() - edgeSize
}

/**
 * 获取屏幕宽度
 */
fun Context.getScreenWidth(isIncludeNav: Boolean = true): Int =
    if (isIncludeNav) {
        getRealDisplaySize().x
    } else {
        getAppDisplaySize().x
    }

/**
 * 获取屏幕高度
 */
fun Context.getScreenHeight(isIncludeNav: Boolean = true): Int =
    if (isIncludeNav) {
        getRealDisplaySize().y
    } else {
        getAppDisplaySize().y
    }

/**
 * Display size excluding system UI (app-usable).
 */
private fun Context.getAppDisplaySize(): Point {
    val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    return Point().also(display::getSize)
}

/**
 * Real display size including system UI.
 */
private fun Context.getRealDisplaySize(): Point {
    val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    return Point().also(display::getRealSize)
}
