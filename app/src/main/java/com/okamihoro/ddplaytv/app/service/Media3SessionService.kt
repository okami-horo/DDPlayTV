package com.okamihoro.ddplaytv.app.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.media3.Media3SessionClient
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import com.xyoye.common_component.services.PlaybackRequestService
import kotlinx.coroutines.flow.StateFlow

/** Metadata/command adapter only. The retained player session still owns the kernel. */
class Media3SessionService : LifecycleService() {
    private val provider: PlaybackRequestService by lazy {
        checkNotNull(ARouter.getInstance().navigation(PlaybackRequestService::class.java))
    }
    private val coordinator by lazy { Media3BackgroundCoordinator { provider.dispatch(it) } }
    private val binder by lazy {
        object : Binder(), Media3SessionClient {
            override fun playback(): StateFlow<PlaybackStateView?> = provider.observePlayback()

            override suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult =
                coordinator.dispatch(command, playback().value)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
}
