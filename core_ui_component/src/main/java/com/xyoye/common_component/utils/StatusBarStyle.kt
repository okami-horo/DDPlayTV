package com.xyoye.common_component.utils

import android.app.Activity
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import com.gyf.immersionbar.BarHide
import com.gyf.immersionbar.ImmersionBar

public object StatusBarStyle {
    public fun applyDefault(
        activity: Activity,
        @ColorRes backgroundColorRes: Int,
        darkFont: Boolean
    ) {
        ImmersionBar
            .with(activity)
            .fitsSystemWindows(true)
            .statusBarDarkFont(darkFont)
            .statusBarColor(backgroundColorRes)
            .navigationBarDarkIcon(darkFont)
            .navigationBarColor(backgroundColorRes)
            .init()
    }

    public fun applyTransparent(
        activity: Activity,
        darkFont: Boolean
    ) {
        ImmersionBar
            .with(activity)
            .transparentBar()
            .statusBarDarkFont(darkFont)
            .init()
    }

    public fun applyTransparentWithTitleBar(
        activity: Activity,
        titleBar: View,
        darkFont: Boolean
    ) {
        ImmersionBar
            .with(activity)
            .titleBar(titleBar, false)
            .transparentBar()
            .statusBarDarkFont(darkFont)
            .init()
    }

    public fun applyStatusBarColorInt(
        activity: Activity,
        @ColorInt statusBarColor: Int,
        darkFont: Boolean,
        fitsSystemWindows: Boolean = false
    ) {
        ImmersionBar
            .with(activity)
            .statusBarColorInt(statusBarColor)
            .fitsSystemWindows(fitsSystemWindows)
            .statusBarDarkFont(darkFont)
            .init()
    }

    public fun applyFullscreenHideStatusBar(activity: Activity) {
        ImmersionBar
            .with(activity)
            .fullScreen(true)
            .hideBar(BarHide.FLAG_HIDE_STATUS_BAR)
            .init()
    }

    public fun applyFullscreenHideAllBars(activity: Activity) {
        ImmersionBar
            .with(activity)
            .fitsSystemWindows(false)
            .fullScreen(true)
            .hideBar(BarHide.FLAG_HIDE_BAR)
            .init()
    }
}
