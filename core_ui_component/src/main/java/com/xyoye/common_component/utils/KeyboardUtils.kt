package com.xyoye.common_component.utils

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.xyoye.common_component.base.app.BaseApplication

/**
 * Created by xyoye on 2020/8/19.
 */

fun showKeyboard(
    view: View,
    flags: Int = 0
) {
    view.isFocusable = true
    view.isFocusableInTouchMode = true
    view.requestFocus()

    // TV 端输入法实现差异很大：避免使用 SHOW_FORCED / toggleSoftInput 强行拉起，
    // 否则可能出现 BACK 隐藏后又被重新拉起等异常体验。
    (
        BaseApplication
            .getAppContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    ).showSoftInput(view, flags)
}

fun hideKeyboard(view: View) {
    (
        BaseApplication
            .getAppContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    ).hideSoftInputFromWindow(view.windowToken, 0)
}
