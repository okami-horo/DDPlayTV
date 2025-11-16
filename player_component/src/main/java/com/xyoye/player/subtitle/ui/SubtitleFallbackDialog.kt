package com.xyoye.player.subtitle.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.os.SystemClock
import com.xyoye.common_component.utils.DDLog
import com.xyoye.player_component.R

/**
 * Blocking dialog requesting user consent before falling back to the legacy backend.
 */
class SubtitleFallbackDialog(
    context: Context,
    private val onAction: (SubtitleFallbackAction) -> Unit
) {
    private var shownAtMs: Long = 0L
    private val dialog: AlertDialog = AlertDialog.Builder(context)
        .setTitle(R.string.subtitle_fallback_title)
        .setMessage(R.string.subtitle_fallback_message)
        .setCancelable(false)
        .setPositiveButton(R.string.subtitle_fallback_switch_action) { _, _ ->
            logRecovery("switch_legacy")
            onAction(SubtitleFallbackAction.SWITCH_TO_LEGACY)
        }
        .setNegativeButton(R.string.subtitle_fallback_retry_action) { _, _ ->
            logRecovery("continue_current")
            onAction(SubtitleFallbackAction.CONTINUE_CURRENT)
        }
        .create()

    fun show() {
        if (!dialog.isShowing) {
            shownAtMs = SystemClock.elapsedRealtime()
            dialog.show()
            DDLog.i("LIBASS-Fallback", "fallback dialog shown at=$shownAtMs")
        }
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun setOnDismissListener(listener: () -> Unit) {
        dialog.setOnDismissListener { listener() }
    }

    enum class SubtitleFallbackAction {
        SWITCH_TO_LEGACY,
        CONTINUE_CURRENT
    }

    private fun logRecovery(action: String) {
        val delta = if (shownAtMs > 0) SystemClock.elapsedRealtime() - shownAtMs else -1
        DDLog.i(
            "LIBASS-Fallback",
            "fallback action=$action elapsed=${if (delta >= 0) "${delta}ms" else "n/a"}"
        )
    }
}
