package com.xyoye.common_component.utils.thunder

import android.content.Context
import android.os.Build
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.*
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.privacy.SensitiveDataSanitizer
import com.xyoye.common_component.storage.file.helper.TorrentBean
import com.xyoye.common_component.storage.transport.TransportAcquisitionException
import com.xyoye.common_component.storage.transport.TransportAcquisitionFailure
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.common_component.utils.MagnetUtils
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.common_component.utils.thunder.model.ThunderTaskInfo
import com.xyoye.common_component.utils.thunder.model.ThunderTaskReadableInfo
import com.xyoye.common_component.utils.thunder.model.ThunderTorrentFileInfo
import com.xyoye.common_component.utils.thunder.model.ThunderTorrentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by xyoye on 2021/11/20.
 */

class ThunderManager internal constructor(
    private val taskOperations: ThunderTaskOperations,
    private val cacheDirectoryProvider: () -> File
) {
    private constructor() : this(NativeThunderTaskOperations(), PathHelper::getPlayCacheDirectory)

    // 下载任务序号
    private val mAtomicInteger = AtomicInteger(0)

    // 下载任务列表
    private val tasks = ConcurrentHashMap<Long, TaskAllocation>()

    // 种子文件存储文件夹
    private val torrentDirectory by lazy { PathHelper.getTorrentDirectory() }

    // 缓存文件存储文件夹
    private val cacheDirectory by lazy { cacheDirectoryProvider() }

    companion object {
        private const val LOG_TAG = "ThunderManager"

        // thunder.aar 内置的 jni ABI（见 repository/thunder/thunder.aar）
        private val AAR_SUPPORTED_ABI = arrayOf("arm64-v8a", "armeabi-v7a")

        private val initLock = Any()

        @Volatile
        private var initAttempted: Boolean = false

        @Volatile
        private var initSucceeded: Boolean = false

        @Volatile
        private var initFailure: Throwable? = null

        // 无效的任务ID
        private const val INVALID_ID = -1L

        // 种子文件下载超时时间
        private const val TIME_OUT_DOWNLOAD_TORRENT = 5 * 1000L

        private val TASK_STATUS_MSG =
            mapOf(
                Pair(XLConstant.XLTaskStatus.TASK_FAILED, "Failed"),
                Pair(XLConstant.XLTaskStatus.TASK_IDLE, "Idle"),
                Pair(XLConstant.XLTaskStatus.TASK_RUNNING, "Running"),
                Pair(XLConstant.XLTaskStatus.TASK_STOPPED, "Stopped"),
                Pair(XLConstant.XLTaskStatus.TASK_SUCCESS, "Success"),
            )

        private val TASK_ERROR_MSG by lazy {
            try {
                JsonHelper.parseJsonMap(ErrorCodeToMsg.ErrCodeToMsg)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "ThunderManager",
                    "TASK_ERROR_MSG",
                    "解析迅雷错误码映射失败",
                )
                emptyMap()
            }
        }

        fun getInstance() = Holder.instance

        /**
         * 判断当前设备是否可能支持迅雷 SDK（仅做 ABI 预检，不会触发 SDK 初始化）。
         */
        fun isPlatformSupported(): Boolean {
            val supported = AAR_SUPPORTED_ABI.toHashSet()
            return Build.SUPPORTED_ABIS.any { supported.contains(it) }
        }

        /**
         * 按需初始化迅雷 SDK，幂等且可降级：
         * - 不支持 ABI：返回 false（不会抛异常）
         * - 初始化失败：返回 false（缓存失败原因，后续不再重复初始化）
         */
        fun ensureInitialized(): Boolean {
            if (initSucceeded) {
                return true
            }
            synchronized(initLock) {
                if (initSucceeded) {
                    return true
                }
                if (initAttempted) {
                    return false
                }
                initAttempted = true

                if (!isPlatformSupported()) {
                    initFailure =
                        IllegalStateException(
                            "Unsupported ABI: device=${Build.SUPPORTED_ABIS.joinToString()} supported=${AAR_SUPPORTED_ABI.joinToString()}",
                        )
                    LogFacade.w(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "thunder init skipped: unsupported ABI",
                        mapOf(
                            "deviceAbis" to Build.SUPPORTED_ABIS.joinToString(),
                            "supportedAbis" to AAR_SUPPORTED_ABI.joinToString(),
                        ),
                    )
                    return false
                }

                return try {
                    XLTaskHelper.init(BaseApplication.getAppContext().applicationContext)
                    initSucceeded = true
                    LogFacade.i(LogModule.STORAGE, LOG_TAG, "thunder init success")
                    true
                } catch (t: Throwable) {
                    initFailure = t
                    LogFacade.e(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "thunder init failed, feature will be disabled",
                        throwable = t,
                    )
                    false
                }
            }
        }

        fun initialize(context: Context) {
            // 兼容旧调用：改为幂等的按需初始化
            if (initSucceeded) return
            synchronized(initLock) {
                if (initSucceeded) return
                if (initAttempted) return
                initAttempted = true
                try {
                    XLTaskHelper.init(context.applicationContext)
                    initSucceeded = true
                } catch (t: Throwable) {
                    initFailure = t
                    LogFacade.e(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "thunder init failed via initialize(context), feature will be disabled",
                        throwable = t,
                    )
                }
            }
        }

        /**
         * 用于 UI/调用方展示的可读降级原因（会触发按需初始化）。
         * @return `null` 表示已就绪，否则返回可展示的错误提示文案
         */
        fun ensureInitializedOrErrorMessage(): String? {
            if (ensureInitialized()) {
                return null
            }
            if (!isPlatformSupported()) {
                return "当前设备不支持迅雷磁链/BT（ABI 不匹配），已禁用该功能"
            }
            val t = initFailure
            return if (t == null) {
                "迅雷磁链/BT 初始化失败，已禁用该功能"
            } else {
                "迅雷磁链/BT 初始化失败（${t.javaClass.simpleName}），已禁用该功能"
            }
        }

        fun media3DownloadId(
            source: String,
            fileIndex: Int = -1
        ): String {
            val normalized =
                if (source.startsWith("magnet")) {
                    MagnetUtils.getMagnetHash(source).ifEmpty { source }
                } else {
                    source
                }
            val combined = if (fileIndex >= 0) "$normalized#$fileIndex" else normalized
            return combined.toMd5String()
        }

        fun readTaskInfo(taskInfo: ThunderTaskInfo): ThunderTaskReadableInfo {
            val statusText = TASK_STATUS_MSG[taskInfo.taskStatus] ?: "Unknown_${taskInfo.taskStatus}"
            val errorCode = taskInfo.errorCode
            val errorText = TASK_ERROR_MSG[errorCode.toString()]?.trim().orEmpty()
            return ThunderTaskReadableInfo(
                statusCode = taskInfo.taskStatus,
                statusText = statusText,
                errorCode = errorCode,
                errorText = errorText,
            )
        }
    }

    object Holder {
        val instance = ThunderManager()
    }

    /**
     * 下载种子文件
     * @param magnet 磁链
     * @return 种子文件路径
     */
    suspend fun downloadTorrentFile(magnet: String): String? {
        if (!ensureInitialized()) {
            return null
        }
        return try {
            val hash = MagnetUtils.getMagnetHash(magnet)
            if (hash.isEmpty()) {
                ErrorReportHelper.postException(
                    "Invalid magnet link format",
                    "ThunderManager",
                    RuntimeException("Magnet link hash extraction failed: ${SensitiveDataSanitizer.sanitizeMagnet(magnet)}"),
                )
                return null
            }

            val torrentTaskParam =
                MagnetTaskParam().apply {
                    setFileName("$hash.torrent")
                    setFilePath(torrentDirectory.absolutePath)
                    setUrl("magnet:?xt=urn:btih:$hash")
                }

            val torrentTaskId = XLTaskHelper.getInstance().addMagnetTask(torrentTaskParam)
            if (torrentTaskId == INVALID_ID) {
                ErrorReportHelper.postException(
                    "Failed to create torrent download task",
                    "ThunderManager",
                    RuntimeException(
                        "XLTaskHelper.addMagnetTask returned INVALID_ID for magnet: ${SensitiveDataSanitizer.sanitizeMagnet(magnet)}",
                    ),
                )
                return null
            }

            waitTorrentDownloaded(torrentTaskId, torrentTaskParam)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ThunderManager",
                "downloadTorrentFile",
                "磁链: ${SensitiveDataSanitizer.sanitizeMagnet(magnet)}",
            )
            null
        }
    }

    private class TaskAllocation(
        val taskId: Long,
        val leaseId: String,
        val directory: File,
        @Volatile var playUrl: String? = null
    )

    internal suspend fun acquirePlaybackTask(
        torrent: TorrentBean,
        index: Int
    ): ThunderPlaybackTask {
        if (!taskOperations.ensureReady()) {
            throw TransportAcquisitionException(TransportAcquisitionFailure.UnsupportedTransport)
        }
        val file =
            torrent.subFileInfo.find { it.fileIndex == index }
                ?: throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
        currentCoroutineContext().ensureActive()
        val leaseId = UUID.randomUUID().toString()
        val root = cacheDirectory.canonicalFile
        val directory = File(root, "lease-$leaseId").canonicalFile
        check(directory.parentFile == root)
        val mediaFile = File(directory, file.fileName).canonicalFile
        if (!mediaFile.path.startsWith(directory.path + File.separator)) {
            throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw TransportAcquisitionException(TransportAcquisitionFailure.TaskCreationFailed)
        }
        var owned: TaskAllocation? = null
        var delivered = false
        try {
            val parameters =
                BtTaskParam().apply {
                    setCreateMode(1)
                    setFilePath(directory.absolutePath)
                    setMaxConcurrent(1)
                    setSeqId(mAtomicInteger.incrementAndGet())
                    setTorrentPath(torrent.torrentPath)
                }
            var taskId = taskOperations.create(parameters)
            if (taskId == INVALID_ID) {
                delay(200)
                taskId = taskOperations.create(parameters)
            }
            if (taskId < 0) throw TransportAcquisitionException(TransportAcquisitionFailure.TaskCreationFailed)
            val allocation = TaskAllocation(taskId, leaseId, directory)
            // Check before selecting/starting: a duplicate native ID must not mutate an active task.
            if (tasks.putIfAbsent(taskId, allocation) != null) {
                throw TransportAcquisitionException(TransportAcquisitionFailure.TaskCreationFailed)
            }
            owned = allocation
            currentCoroutineContext().ensureActive()
            val deselectedIndexes =
                torrent.subFileInfo.filter { it.fileIndex != index }.map { it.fileIndex }.toIntArray()
            taskOperations.select(taskId, intArrayOf(index), deselectedIndexes)
            taskOperations.start(taskId)
            if (!taskOperations.awaitStarted(taskId)) {
                throw TransportAcquisitionException(TransportAcquisitionFailure.TaskCreationFailed)
            }
            val url = taskOperations.localUrl(mediaFile.absolutePath)
            if (url.isBlank()) throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
            currentCoroutineContext().ensureActive()
            allocation.playUrl = url
            delivered = true
            return ThunderPlaybackTask(leaseId, taskId, url, torrent.torrentPath, index)
        } finally {
            if (!delivered) {
                val allocation = owned
                if (allocation == null) {
                    releaseTransportResources(leaseId, listOf({
                        directory.deleteRecursively()
                        Unit
                    }))
                } else {
                    releasePlaybackTask(allocation.taskId, allocation.leaseId)
                }
            }
        }
    }

    internal fun releasePlaybackTask(
        taskId: Long,
        leaseId: String
    ) {
        val allocation = tasks[taskId]?.takeIf { it.leaseId == leaseId } ?: return
        if (!tasks.remove(taskId, allocation)) return
        releaseTransportResources(leaseId, listOf({ taskOperations.stop(taskId) }, {
            // Only a generated child directory is ever owned by an individual task.
            check(allocation.directory.parentFile == cacheDirectory.canonicalFile)
            allocation.directory.deleteRecursively()
            Unit
        }))
    }

    /**
     * 等待种子下载完成，超时5秒
     */
    private suspend fun waitTorrentDownloaded(
        torrentTaskId: Long,
        param: MagnetTaskParam
    ): String? {
        return try {
            withTimeoutOrNull(TIME_OUT_DOWNLOAD_TORRENT) {
                var taskInfo = XLTaskHelper.getInstance().getTaskInfo(torrentTaskId)
                while (taskInfo.mTaskStatus == XLConstant.XLTaskStatus.TASK_IDLE ||
                    taskInfo.mTaskStatus == XLConstant.XLTaskStatus.TASK_RUNNING
                ) {
                    delay(300L)
                    taskInfo = XLTaskHelper.getInstance().getTaskInfo(torrentTaskId)
                }
                return@withTimeoutOrNull if (
                    taskInfo.mTaskStatus == XLConstant.XLTaskStatus.TASK_SUCCESS
                ) {
                    "${param.mFilePath}/${param.mFileName}"
                } else {
                    ErrorReportHelper.postException(
                        "Torrent download failed",
                        "ThunderManager",
                        RuntimeException("Task status: ${taskInfo.mTaskStatus}, error code: ${taskInfo.mErrorCode}"),
                    )
                    null
                }
            } ?: run {
                ErrorReportHelper.postException(
                    "Torrent download timeout",
                    "ThunderManager",
                    RuntimeException("Timeout waiting for torrent download, task ID: $torrentTaskId"),
                )
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ThunderManager",
                "waitTorrentDownloaded",
                "任务ID: $torrentTaskId, 文件名: ${param.mFileName}",
            )
            null
        } finally {
            releaseTransportResources("thunder-metadata", listOf({ XLTaskHelper.getInstance().stopTask(torrentTaskId) }))
        }
    }

    /**
     * 获取种子文件信息
     */
    fun getTaskInfo(torrentPath: String): ThunderTorrentInfo =
        try {
            if (!ensureInitialized()) {
                throw IllegalStateException(ensureInitializedOrErrorMessage() ?: "Thunder is not initialized")
            }
            val torrentInfo = XLTaskHelper.getInstance().getTorrentInfo(torrentPath)
            ThunderTorrentInfo(
                fileCount = torrentInfo.mFileCount,
                infoHash = torrentInfo.mInfoHash,
                isMultiFiles = torrentInfo.mIsMultiFiles,
                multiFileBaseFolder = torrentInfo.mMultiFileBaseFolder,
                subFileInfo =
                    torrentInfo.mSubFileInfo
                        ?.map {
                            ThunderTorrentFileInfo(
                                fileIndex = it.mFileIndex,
                                fileName = it.mFileName,
                                fileSize = it.mFileSize,
                                subPath = it.mSubPath,
                            )
                        }
                        .orEmpty(),
            )
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ThunderManager",
                "getTaskInfo",
                "种子路径: $torrentPath",
            )
            throw e
        }

    /**
     * 获取下载任务信息
     */
    fun getTaskInfo(taskId: Long): ThunderTaskInfo =
        try {
            if (!ensureInitialized()) {
                throw IllegalStateException(ensureInitializedOrErrorMessage() ?: "Thunder is not initialized")
            }
            val taskInfo = XLTaskHelper.getInstance().getTaskInfo(taskId)
            ThunderTaskInfo(
                taskStatus = taskInfo.mTaskStatus,
                errorCode = taskInfo.mErrorCode,
            )
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "ThunderManager",
                "getTaskInfo",
                "任务ID: $taskId",
            )
            throw e
        }

    /**
     * 获取下载任务ID
     */
    fun getTaskIdForPlayUrl(playUrl: String): Long = tasks.values.firstOrNull { it.playUrl == playUrl }?.taskId ?: INVALID_ID
}
