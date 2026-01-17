package com.xyoye.player.utils

import androidx.media3.common.PlaybackException

object PlaybackErrorFormatter {
    fun format(error: Throwable?): String =
        when (error) {
            null -> "<null>"
            is PlaybackException -> formatPlaybackException(error)
            else -> "${error.javaClass.name}: ${error.message ?: ""}".trimEnd()
        }

    fun formatPlaybackException(error: PlaybackException): String {
        val cause = error.cause
        val causeDesc = cause?.let { "${it.javaClass.name}: ${it.message ?: ""}".trimEnd() } ?: "null"
        return "PlaybackException(errorCodeName=${error.errorCodeName} + cause=$causeDesc)"
    }
}
