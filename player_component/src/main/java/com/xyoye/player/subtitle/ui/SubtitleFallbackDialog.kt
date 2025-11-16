package com.xyoye.player.subtitle.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.xyoye.player_component.R

/**
 * Blocking dialog requesting user consent before falling back to the legacy backend.
 */
class SubtitleFallbackDialog(
    context: Context,
    private val onAction: (SubtitleFallbackAction) -> Unit
) {
    private val dialog: AlertDialog = AlertDialog.Builder(context)
        .setTitle(R.string.subtitle_fallback_title)
        .setMessage(R.string.subtitle_fallback_message)
        .setCancelable(false)
        .setPositiveButton(R.string.subtitle_fallback_switch_action) { _, _ ->
            onAction(SubtitleFallbackAction.SWITCH_TO_LEGACY)
        }
        .setNegativeButton(R.string.subtitle_fallback_retry_action) { _, _ ->
            onAction(SubtitleFallbackAction.CONTINUE_CURRENT)
        }
        .create()

    fun show() {
        if (!dialog.isShowing) {
            dialog.show()
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
}
