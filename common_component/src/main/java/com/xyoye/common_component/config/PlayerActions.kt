package com.xyoye.common_component.config

import android.content.Context
import android.content.Intent

object PlayerActions {
    const val ACTION_EXIT_PLAYER = "com.xyoye.dandanplay.action.EXIT_PLAYER"

    const val EXTRA_STORAGE_ID = "extra_storage_id"

    fun sendExitPlayer(
        context: Context,
        storageId: Int,
    ) {
        context.sendBroadcast(
            Intent(ACTION_EXIT_PLAYER)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STORAGE_ID, storageId),
        )
    }
}
