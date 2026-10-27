package com.xyoye.storage_component.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.bridge.ServiceLifecycleBridge
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.extension.isServiceRunning
import com.xyoye.common_component.extension.resumeWhenAlive
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.playback.PlaybackEntryRequests
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.source.handoff.PlaybackReferenceFactory
import com.xyoye.common_component.storage.screencast.receiver.HttpServer
import com.xyoye.common_component.storage.screencast.receiver.ScreencastReceiveHandler
import com.xyoye.common_component.storage.screencast.receiver.UdpServer
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Created by xyoye on 2022/9/16
 */

class ScreencastReceiveService :
    Service(),
    ScreencastReceiveHandler {
    private var httpServer: HttpServer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackScope = CoroutineScope(serviceScope.coroutineContext + Dispatchers.Main.immediate)
    private val playbackRequests =
        PlaybackEntryRequests(playbackScope) { input ->
            val result =
                ARouter.getInstance().navigation(PlaybackRequestService::class.java)?.submit(input)
                    ?: PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
            if (result is PlaybackSubmissionResult.Accepted) {
                val descriptor = input.descriptor as ScreencastDescriptor
                notifier.showReceivedVideo(descriptor.relatedVideos.first { it.uniqueKey == descriptor.playUniqueKey }.title)
            }
            result
        }

    private lateinit var notifier: ScreencastReceiverNotifier
    private lateinit var multicastLock: WifiManager.MulticastLock
    private var multicastJob: Job? = null

    companion object {
        private const val KEY_SERVER_PORT = "key_server_port"
        private const val KEY_SERVER_PASSWORD = "key_server_password"

        fun isRunning(context: Context): Boolean = context.isServiceRunning(ScreencastReceiveService::class.java)

        fun start(
            context: Context,
            httpPort: Int,
            password: String?
        ) {
            val intent = Intent(context, ScreencastReceiveService::class.java)
            intent.putExtra(KEY_SERVER_PORT, httpPort)
            intent.putExtra(KEY_SERVER_PASSWORD, password)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreencastReceiveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceLifecycleBridge.onScreencastReceiveLifeChange(true)

        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = manager.createMulticastLock("udp_multicast")
        multicastLock.acquire()

        notifier = ScreencastReceiverNotifier(this)
        playbackScope.launch {
            playbackRequests.outcomes.collect { result ->
                when (result) {
                    is PlaybackSubmissionResult.Accepted ->
                        ARouter
                            .getInstance()
                            .build(RouteTable.Player.Player)
                            .withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                            .withFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .navigation(this@ScreencastReceiveService)
                    is PlaybackSubmissionResult.Rejected -> ToastCenter.showError(result.failure.userMessage())
                }
            }
        }
        startForeground(Notifications.Id.SCREENCAST_RECEIVE, notifier.notificationBuilder.build())
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val httpPort: Int? = intent?.extras?.getInt(KEY_SERVER_PORT)
        val password: String? = intent?.extras?.getString(KEY_SERVER_PASSWORD)
        // 必须设置端口
        if (httpPort == null || httpPort == 0) {
            return super.onStartCommand(intent, flags, startId)
        }
        // 已启动则不再启动
        if (httpServer?.isAlive == true) {
            return super.onStartCommand(intent, flags, startId)
        }
        // 停止旧服务
        stopHttpServer()
        // 创建新服务
        val newHttpServer = createHttpServer(httpPort, password)
        if (newHttpServer == null) {
            stopSelf()
            return START_STICKY
        }
        // 启动UDP组播
        startMulticast(newHttpServer.listeningPort, password)

        ToastCenter.showSuccess("投屏接收服务已启动")
        httpServer = newHttpServer
        return START_STICKY
    }

    override fun onDestroy() {
        multicastLock.release()
        stopHttpServer()
        UdpServer.stopMulticastEmit()
        multicastJob?.cancel()
        serviceScope.cancel()
        ServiceLifecycleBridge.onScreencastReceiveLifeChange(false)
        super.onDestroy()
    }

    /**
     * 处理投屏
     */
    override fun onReceiveVideo(screencastData: ScreencastData) {
        val targetVideo = screencastData.relatedVideos.firstOrNull { it.uniqueKey == screencastData.playUniqueKey } ?: return
        if (screencastData.ip.isNullOrBlank()) {
            ToastCenter.showError("播放失败，未知的投屏源地址")
            return
        }
        val descriptor = PlaybackReferenceFactory.fromScreencast(screencastData)
        playbackScope.launch {
            playbackRequests.submit {
                if (!considerAcceptScreencast(targetVideo)) throw CancellationException("Screencast declined")
                PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.SCREENCAST)
            }
        }
    }

    /**
     * 启动UDP组播
     */
    private fun startMulticast(
        port: Int,
        password: String?
    ) {
        multicastJob?.cancel()
        multicastJob =
            serviceScope.launch {
                UdpServer.startMulticastEmit(
                    port,
                    needPassword = password.isNullOrEmpty().not()
                )
            }
    }

    /**
     * 弹窗确认接收投屏
     */
    private suspend fun considerAcceptScreencast(videoData: ScreencastVideoData): Boolean {
        // 设置了自动接收
        if (ScreencastConfig.isReceiveNeedConfirm().not()) {
            return true
        }

        // 获取当前正在展示的Activity
        val topActivity = ActivityHelper.instance.getTopActivity()
        if (topActivity == null) {
            ToastCenter.showError("接收到投屏请求，应用处理失败")
            return false
        }

        // 展示弹窗询问是否接收投屏
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val dialog =
                    AlertDialog
                        .Builder(topActivity)
                        .setTitle("接收到投屏请求，是否播放？")
                        .setMessage("投屏内容：${videoData.title}")
                        .setNegativeButton("忽略") { dialog, _ ->
                            continuation.resumeWhenAlive(false)
                            dialog.dismiss()
                        }.setPositiveButton("播放") { dialog, _ ->
                            continuation.resumeWhenAlive(true)
                            dialog.dismiss()
                        }.create()
                dialog.setOnDismissListener { continuation.resumeWhenAlive(false) }
                continuation.invokeOnCancellation { topActivity.runOnUiThread { dialog.dismiss() } }
                dialog.show()
            }
        }
    }

    private fun stopHttpServer() {
        httpServer?.setScreenReceiveHandler(null)
        httpServer?.stop()
    }

    private fun createHttpServer(
        port: Int,
        password: String?
    ): HttpServer? =
        try {
            val httpServer = HttpServer(password, port)
            // Install the receiver before opening the socket; the first request may arrive immediately.
            httpServer.setScreenReceiveHandler(this)
            httpServer.start(2000)
            httpServer
        } catch (e: Exception) {
            // 上报投屏接收HTTP服务器创建异常
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ScreencastReceiveService",
                "createHttpServer",
                "创建投屏接收HTTP服务器失败，port=$port, hasPassword=${password != null}"
            )
            null
        }
}
