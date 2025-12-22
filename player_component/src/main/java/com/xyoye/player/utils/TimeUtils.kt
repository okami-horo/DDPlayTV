package com.xyoye.player.utils

import java.util.Locale

/**
 * Created by xyoye on 2020/11/13.
 */

fun formatDuration(time: Long): String {
    val totalSeconds = (time / 1000).toInt()
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return if (minutes > 99) {
        String.format(
            Locale.getDefault(),
            "%d:%02d",
            minutes,
            seconds,
        )
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
