package com.xyoye.common_component.storage.file.helper

import com.xunlei.downloadlib.parameter.ErrorCodeToMsg.ErrCodeToMsg
import com.xunlei.downloadlib.parameter.XLConstant
import com.xyoye.common_component.bridge.PlayTaskBridge
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.utils.thunder.ThunderManager
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2021/8/8.
 */

object PlayTaskManager {
    private var isInitialed = false

    private var TASK_ERROR_MSG =
        try {
            JsonHelper.parseJsonMap(ErrCodeToMsg)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "PlayTaskManager",
                "init",
                "解析错误代码映射失败",
            )
            emptyMap()
        }

    private var TASK_STATUS_MSG =
        mapOf(
            Pair(XLConstant.XLTaskStatus.TASK_FAILED, "Failed"),
            Pair(XLConstant.XLTaskStatus.TASK_IDLE, "Idle"),
            Pair(XLConstant.XLTaskStatus.TASK_RUNNING, "Running"),
            Pair(XLConstant.XLTaskStatus.TASK_STOPPED, "Stopped"),
            Pair(XLConstant.XLTaskStatus.TASK_SUCCESS, "Success"),
        )

    fun init() {
        if (isInitialed) {
            return
        }

        try {
            isInitialed = true

            SupervisorScope.Main.launch {
                try {
                    PlayTaskBridge.taskRemoveLiveData.observeForever {
                        onPlayTaskRemove(it)
                    }
                } catch (e: Exception) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "PlayTaskManager",
                        "init",
                        "设置任务移除监听器失败",
                    )
                }
            }

            PlayTaskBridge.taskInfoQuery = { id ->
                try {
                    val taskInfo = ThunderManager.getInstance().getTaskInfo(id)
                    val status = TASK_STATUS_MSG[taskInfo.mTaskStatus] ?: "Unknown_${taskInfo.mTaskStatus}"
                    val code = taskInfo.mErrorCode.toString()
                    val msg = TASK_ERROR_MSG[code]?.trim() ?: ""
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

    private fun onPlayTaskRemove(taskId: Long) {
        try {
            SupervisorScope.IO.launch {
                try {
                    ThunderManager.getInstance().stopTask(taskId)
                } catch (e: Exception) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "PlayTaskManager",
                        "onPlayTaskRemove",
                        "停止任务失败，任务ID: $taskId",
                    )
                }
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "PlayTaskManager",
                "onPlayTaskRemove",
                "创建协程失败，任务ID: $taskId",
            )
        }
    }
}
