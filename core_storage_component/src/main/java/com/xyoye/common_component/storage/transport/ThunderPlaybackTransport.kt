package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.common_component.utils.thunder.ThunderPlaybackTask

internal class ThunderPlaybackTransport(
    task: ThunderPlaybackTask,
    manager: ThunderManager
) : PlaybackTransportLease by ManagedPlaybackTransportLease(
        task.playUrl,
        PlaybackTransportType.THUNDER,
        listOf({ manager.releasePlaybackTask(task.taskId, task.leaseId) }),
        task.leaseId
    ) {
    val taskId: Long = task.taskId
}
