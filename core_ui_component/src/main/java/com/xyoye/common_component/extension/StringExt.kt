package com.xyoye.common_component.extension

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.weight.ToastCenter

/**
 * Created by xyoye on 2021/3/20.
 */

fun String.addToClipboard() {
    val clipboard =
        BaseApplication
            .getAppContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText("data", this)
    clipboard.setPrimaryClip(clipData)
}

fun String.toastError() {
    ToastCenter.showError(this)
}
