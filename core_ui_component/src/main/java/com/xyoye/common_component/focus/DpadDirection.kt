package com.xyoye.common_component.focus

import android.view.KeyEvent

enum class DpadDirection {
    Up,
    Down,
    Left,
    Right,
}

fun Int.toDpadDirectionOrNull(): DpadDirection? =
    when (this) {
        KeyEvent.KEYCODE_DPAD_UP -> DpadDirection.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> DpadDirection.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> DpadDirection.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> DpadDirection.Right
        else -> null
    }
