package com.xyoye.common_component.bridge

/**
 * Created by xyoye on 2021/8/8.
 */

object PlayTaskBridge {
    var taskInfoQuery: ((id: Long) -> String)? = null

    fun getTaskLog(id: Long): String = taskInfoQuery?.invoke(id) ?: ""
}
