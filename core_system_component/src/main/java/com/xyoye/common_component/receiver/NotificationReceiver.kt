package com.xyoye.common_component.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.services.ScreencastReceiveService

/**
 * Created by xyoye on 2022/9/14
 */

class NotificationReceiver : BroadcastReceiver() {
    @Autowired
    lateinit var screencastReceiveService: ScreencastReceiveService

    companion object {
        private fun cancelScreencastReceiveAction(context: Context): String =
            "${context.packageName}.CANCEL_SCREENCAST_RECEIVE"

        /**
         * 关闭投屏接收服务
         */
        fun cancelScreencastReceivePendingBroadcast(context: Context): PendingIntent {
            val intent =
                Intent(context, NotificationReceiver::class.java).apply {
                    action = cancelScreencastReceiveAction(context)
                }

            val flag =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            return PendingIntent.getBroadcast(context, 0, intent, flag)
        }
    }

    init {
        ARouter.getInstance().inject(this)
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        when (intent.action) {
            cancelScreencastReceiveAction(context) ->
                cancelScreencastReceive(
                    context,
                    Notifications.Id.SCREENCAST_RECEIVE,
                )
        }
    }

    /**
     * 关闭投屏内容接收服务
     */
    private fun cancelScreencastReceive(
        context: Context,
        notificationId: Int
    ) {
        screencastReceiveService.stopService(context)
        ContextCompat.getMainExecutor(context).execute {
            dismissNotification(context, notificationId)
        }
    }

    /**
     * 关闭通知
     */
    private fun dismissNotification(
        context: Context,
        notificationId: Int
    ) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}
