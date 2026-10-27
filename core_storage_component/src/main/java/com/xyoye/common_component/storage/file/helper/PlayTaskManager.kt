package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.bridge.PlayTaskBridge
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.thunder.ThunderManager

/**
 * Created by xyoye on 2021/8/8.
 */

object PlayTaskManager {
    private var isInitialed = false

    fun init() {
        if (isInitialed) {
            return
        }

        try {
            isInitialed = true

            PlayTaskBridge.taskInfoQuery = { id ->
                try {
                    val taskInfo = ThunderManager.getInstance().getTaskInfo(id)
                    val readableInfo = ThunderManager.readTaskInfo(taskInfo)
                    val code = readableInfo.errorCode.toString()
                    val status = readableInfo.statusText
                    val msg = readableInfo.errorText
                    "\n[$status, 0x$code]\n[$msg]"
                } catch (e: Exception) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "PlayTaskManager",
                        "taskInfoQuery",
                        "任务ID: $id",
                    )
                    "\n[Error, 0x0]\n[获取任务信息失败]"
                }
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "PlayTaskManager",
                "init",
                "初始化PlayTaskManager失败",
            )
            isInitialed = false
        }
    }

}
