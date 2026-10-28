package com.xyoye.common_component.focus

import android.view.View
import androidx.annotation.MainThread
import com.xyoye.common_component.extension.isTelevisionUiMode

/**
 * TV/非触摸（DPAD）模式下的通用输入/焦点策略。
 */
@MainThread
fun View.applyDpadEditTextPolicy(enabled: Boolean = context.isTelevisionUiMode() && !isInTouchMode) {
    if (!enabled) return

    clearFocus()
    isFocusable = false
    isFocusableInTouchMode = false
}
