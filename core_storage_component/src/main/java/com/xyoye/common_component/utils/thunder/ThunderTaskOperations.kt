package com.xyoye.common_component.utils.thunder

import com.xunlei.downloadlib.XLDownloadManager
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.BtIndexSet
import com.xunlei.downloadlib.parameter.BtTaskParam
import com.xunlei.downloadlib.parameter.GetTaskId
import com.xunlei.downloadlib.parameter.XLConstant
import com.xunlei.downloadlib.parameter.XLTaskLocalUrl
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import kotlinx.coroutines.delay
import java.io.IOException

/** Native operations are injectable; task ownership stays in ThunderManager. */
internal interface ThunderTaskOperations {
    fun ensureReady(): Boolean

    fun create(parameters: BtTaskParam): Long

    fun select(
        taskId: Long,
        selected: IntArray,
        deselected: IntArray
    )

    fun start(taskId: Long)

    suspend fun awaitStarted(taskId: Long): Boolean

    fun localUrl(path: String): String

    fun stop(taskId: Long)
}

internal class NativeThunderTaskOperations : ThunderTaskOperations {
    private val sdk: XLDownloadManager get() = XLDownloadManager.getInstance()

    override fun ensureReady(): Boolean = ThunderManager.ensureInitialized()

    override fun create(parameters: BtTaskParam): Long {
        val task = GetTaskId()
        return if (sdk.createBtTask(parameters, task) == XLConstant.XLErrorCode.NO_ERROR) task.taskId else -1
    }

    override fun select(
        taskId: Long,
        selected: IntArray,
        deselected: IntArray
    ) {
        checkResult(sdk.selectBtSubTask(taskId, BtIndexSet(selected.size).apply { mIndexSet = selected }))
        if (deselected.isNotEmpty()) {
            checkResult(sdk.deselectBtSubTask(taskId, BtIndexSet(deselected.size).apply { mIndexSet = deselected }))
        }
    }

    override fun start(taskId: Long) {
        // Match XLTaskHelper: offline-task hints are advisory for ordinary BT tasks.
        sdk.setTaskLxState(taskId, 0, 1)
        checkResult(sdk.startTask(taskId, false))
    }

    override suspend fun awaitStarted(taskId: Long): Boolean {
        delay(2000)
        val info = XLTaskHelper.getInstance().getTaskInfo(taskId)
        if (info.mTaskStatus == XLConstant.XLTaskStatus.TASK_FAILED) {
            LogFacade.w(
                LogModule.STORAGE,
                "ThunderManager",
                "Native task failed to start",
                mapOf("errorCode" to info.mErrorCode.toString())
            )
            return false
        }
        return true
    }

    override fun localUrl(path: String): String {
        val result = XLTaskLocalUrl()
        checkResult(sdk.getLocalUrl(path, result))
        return result.mStrUrl.orEmpty()
    }

    override fun stop(taskId: Long) {
        try {
            checkResult(sdk.stopTask(taskId))
        } finally {
            checkResult(sdk.releaseTask(taskId))
        }
    }

    private fun checkResult(code: Int) {
        if (code != XLConstant.XLErrorCode.NO_ERROR) throw IOException("Thunder operation failed: $code")
    }
}

/** Metadata returned on successful acquisition; only its lease retires the task. */
internal data class ThunderPlaybackTask(
    val leaseId: String,
    val taskId: Long,
    val playUrl: String,
    val torrentPath: String,
    val fileIndex: Int
)
