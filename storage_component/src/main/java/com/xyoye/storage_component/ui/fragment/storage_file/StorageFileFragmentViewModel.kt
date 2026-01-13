package com.xyoye.storage_component.ui.fragment.storage_file

import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanReAuthRequiredException
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.payloadAs
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.bilibili.BilibiliHistoryItem
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.TrackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class StorageFileFragmentViewModel : BaseViewModel() {
    companion object {
        private val remoteHistoryOverrideThresholdMs = TimeUnit.MINUTES.toMillis(1)
        private const val remoteHistoryPositionToleranceMs = 3_000L

        private val lastPlayDirectory =
            PlayHistoryEntity(
                url = "",
                mediaType = MediaType.OTHER_STORAGE,
                videoName = "",
            ).apply {
                isLastPlay = true
            }
    }

    private val _fileLiveData = MutableLiveData<List<Any>>()
    val fileLiveData: LiveData<List<Any>> = _fileLiveData

    private val _loginRequiredLiveData = MutableLiveData<MediaLibraryEntity>()
    val loginRequiredLiveData: LiveData<MediaLibraryEntity> = _loginRequiredLiveData

    lateinit var storage: Storage

    @Volatile
    var lastListError: String? = null
        private set

    // 当前媒体库中最后一次播放记录
    private var storageLastPlay: PlayHistoryEntity? = null

    // 是否隐藏.开头的文件
    private val hidePointFile = AppConfig.isShowHiddenFile().not()

    // 文件列表快照
    private var filesSnapshot = listOf<Any>()

    /**
     * 展开文件夹
     */
    fun listFile(
        directory: StorageFile?,
        refresh: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            lastListError = null
            try {
                val authStorage = storage as? AuthStorage
                if (authStorage != null && authStorage.requiresLogin(directory) && !authStorage.isConnected()) {
                    ToastCenter.showWarning("请先${authStorage.loginActionText(directory)}")
                    _loginRequiredLiveData.postValue(storage.library)
                    emptyList<Any>()
                        .apply { _fileLiveData.postValue(this) }
                        .also { filesSnapshot = it }
                    return@launch
                }

                val target = directory ?: storage.getRootFile()
                if (target == null) {
                    emptyList<Any>()
                        .apply { _fileLiveData.postValue(this) }
                        .also { filesSnapshot = it }
                    return@launch
                }

                if (authStorage != null && authStorage.requiresLogin(target) && !authStorage.isConnected()) {
                    ToastCenter.showWarning("请先${authStorage.loginActionText(target)}")
                    _loginRequiredLiveData.postValue(storage.library)
                    emptyList<Any>()
                        .apply { _fileLiveData.postValue(this) }
                        .also { filesSnapshot = it }
                    return@launch
                }

                refreshStorageLastPlay()
                storage
                    .openDirectory(target, refresh)
                    .filter { isDisplayFile(it) }
                    .let { files ->
                        // Bilibili 历史列表需要按时间倒序展示；其分P列表也应保留服务端顺序
                        if (storage.library.mediaType == MediaType.BILIBILI_STORAGE) {
                            files
                        } else {
                            files.sortedWith(StorageSortOption.comparator())
                        }
                    }.onEach { it.playHistory = getHistory(it) }
                    .let { buildDisplayItems(it) }
                    .apply { _fileLiveData.postValue(this) }
                    .also { filesSnapshot = it }
            } catch (e: Exception) {
                lastListError = e.message
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "StorageFileFragmentViewModel",
                    "listFile",
                    "加载文件列表失败: ${directory?.fileName() ?: "root"}",
                )

                if (handleLoginRequiredIfNeeded(e, clearList = true)) {
                    return@launch
                }

                ToastCenter.showError("加载文件列表失败: ${e.message}")
                // 刷新失败时保留旧数据，避免列表突然清空导致不可操作（尤其是 TV 场景）
                if (refresh && filesSnapshot.isNotEmpty()) {
                    _fileLiveData.postValue(filesSnapshot)
                } else {
                    _fileLiveData.postValue(emptyList())
                }
            }
        }
    }

    /**
     * 修改文件排序
     */
    fun changeSortOption() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                lastListError = null
                val currentFiles = _fileLiveData.value?.filterIsInstance<StorageFile>() ?: return@launch
                val sorted =
                    mutableListOf<StorageFile>()
                        .plus(currentFiles)
                        .sortedWith(StorageSortOption.comparator())
                val items = buildDisplayItems(sorted)
                _fileLiveData.postValue(items)
                filesSnapshot = items
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "StorageFileFragmentViewModel",
                    "changeSortOption",
                    "文件排序失败",
                )
                ToastCenter.showError("文件排序失败: ${e.message}")
            }
        }
    }

    /**
     * 搜索文件
     */
    fun searchByText(text: String) {
        if (text.isEmpty()) {
            _fileLiveData.postValue(filesSnapshot)
            return
        }

        // 媒体库支持文件搜索，由媒体库处理搜索
        if (storage.supportSearch()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    lastListError = null
                    refreshStorageLastPlay()
                    storage
                        .search(text)
                        .filter { isDisplayFile(it) }
                        .sortedWith(StorageSortOption.comparator())
                        .onEach { it.playHistory = getHistory(it) }
                        .let { files ->
                            _fileLiveData.postValue(files.map { it as Any })
                        }
                } catch (e: Exception) {
                    if (handleLoginRequiredIfNeeded(e, clearList = true)) {
                        return@launch
                    }
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "StorageFileFragmentViewModel",
                        "searchByText",
                        "搜索文件失败: $text",
                    )
                    ToastCenter.showError("搜索失败: ${e.message}")
                    // 发生错误时显示空列表
                    _fileLiveData.postValue(emptyList())
                }
            }
            return
        }

        // 在当前文件列表进行搜索
        val currentFiles = _fileLiveData.value?.filterIsInstance<StorageFile>() ?: return
        mutableListOf<StorageFile>()
            .plus(currentFiles)
            .filter { it.fileName().contains(text) }
            .let { files ->
                _fileLiveData.postValue(files.map { it as Any })
            }
    }

    /**
     * 绑定音频文件
     */
    fun bindAudioSource(
        file: StorageFile,
        audioPath: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val playHistory = getStorageFileHistory(file)
            playHistory.audioPath = audioPath
            DatabaseManager.instance.getPlayHistoryDao().insert(playHistory)

            // 更新文件列表的播放历史
            updateHistory()
        }
    }

    /**
     * 解绑资源文件
     */
    fun unbindExtraSource(
        file: StorageFile,
        resource: TrackType
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            when (resource) {
                TrackType.DANMU -> {
                    DatabaseManager.instance.getPlayHistoryDao().updateDanmu(
                        file.uniqueKey(),
                        storage.library.id,
                        null,
                        null,
                    )
                }

                TrackType.SUBTITLE -> {
                    DatabaseManager.instance.getPlayHistoryDao().updateSubtitle(
                        file.uniqueKey(),
                        storage.library.id,
                        null,
                    )
                }

                TrackType.AUDIO -> {
                    DatabaseManager.instance.getPlayHistoryDao().updateAudio(
                        file.uniqueKey(),
                        file.storage.library.id,
                        null,
                    )
                }

                TrackType.VIDEO -> Unit
            }
            updateHistory()
        }
    }

    /**
     * 更新文件相关的播放历史
     */
    fun updateHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val fileList = _fileLiveData.value?.filterIsInstance<StorageFile>() ?: return@launch

            refreshStorageLastPlay()
            val updated =
                fileList
                    .map {
                        val history = getHistory(it)
                        val isSameHistory =
                            if (it.isFile()) {
                                it.playHistory == history && it.playHistory?.isLastPlay == history?.isLastPlay
                            } else {
                                it.playHistory?.id == history?.id
                            }
                        if (isSameHistory) {
                            return@map it
                        }
                        // 历史记录不一致时，返回拷贝的新对象
                        it.clone().apply { playHistory = history }
                    }
            val items = buildDisplayItems(updated)
            _fileLiveData.postValue(items)
            filesSnapshot = items
        }
    }

    fun loadMore(showFailureToast: Boolean = true) {
        val pagedStorage = storage as? PagedStorage ?: return
        if (pagedStorage.state == PagedStorage.State.LOADING) {
            return
        }
        if (!pagedStorage.hasMore()) {
            pagedStorage.state = PagedStorage.State.NO_MORE
            _fileLiveData.postValue(buildDisplayItems(_fileLiveData.value?.filterIsInstance<StorageFile>().orEmpty()))
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            pagedStorage.state = PagedStorage.State.LOADING
            _fileLiveData.postValue(buildDisplayItems(_fileLiveData.value?.filterIsInstance<StorageFile>().orEmpty()))

            val current = _fileLiveData.value?.filterIsInstance<StorageFile>().orEmpty()
            val result = pagedStorage.loadMore()
            if (result.isFailure) {
                val exception = result.exceptionOrNull()
                if (exception != null && handleLoginRequiredIfNeeded(exception, clearList = false)) {
                    _fileLiveData.postValue(buildDisplayItems(current))
                    return@launch
                }
                if (showFailureToast) {
                    ToastCenter.showError("加载更多失败: ${exception?.message}")
                }
                _fileLiveData.postValue(buildDisplayItems(current))
                return@launch
            }

            val appended = result.getOrNull().orEmpty()
                .filter { isDisplayFile(it) }
            val existingKeys = current.map { it.uniqueKey() }.toHashSet()
            val merged =
                current.toMutableList<StorageFile>().apply {
                    appended.forEach { file ->
                        if (existingKeys.add(file.uniqueKey())) {
                            file.playHistory = getHistory(file)
                            add(file)
                        }
                    }
                }

            val displayFiles =
                if (storage.library.mediaType == MediaType.BAIDU_PAN_STORAGE) {
                    merged.sortedWith(StorageSortOption.comparator())
                } else {
                    merged
                }

            val items = buildDisplayItems(displayFiles)
            _fileLiveData.postValue(items)
            filesSnapshot = items
        }
    }

    /**
     * 获取文件播放历史
     */
    private suspend fun getHistory(file: StorageFile): PlayHistoryEntity? {
        if (file.isDirectory()) {
            return lastPlayDirectoryHistory(file)
        }
        if (file.isVideoFile().not()) {
            return null
        }

        val bilibiliRemoteHistory =
            file
                .payloadAs<BilibiliHistoryItem>()
                .takeIf { storage.library.mediaType == MediaType.BILIBILI_STORAGE }

        var history: PlayHistoryEntity? =
            DatabaseManager.instance
                .getPlayHistoryDao()
                .getPlayHistory(file.uniqueKey(), file.storage.library.id)
        if (history == null) {
            // 这是一步补救措施，数据库11版本之前，没有存储storageId字段
            // 因此为了避免弹幕等历史数据无法展示，依旧需要通过mediaType查询
            history =
                DatabaseManager.instance
                    .getPlayHistoryDao()
                    .getPlayHistory(file.uniqueKey(), file.storage.library.mediaType)
            // 补充storageId字段
            if (history != null) {
                history.storageId = file.storage.library.id
                DatabaseManager.instance.getPlayHistoryDao().insert(history)
            }
        }
        if (history != null && storageLastPlay != null) {
            history.isLastPlay = history.id == storageLastPlay!!.id
        }

        bilibiliRemoteHistory?.let { remote ->
            val remotePositionMs = normalizeRemoteProgress(remote.progressSec, remote.durationSec) * 1000
            val remoteDurationMs = remote.durationSec.coerceAtLeast(0) * 1000
            val remoteViewAtMs = remote.viewAt.coerceAtLeast(0) * 1000

            val local = history
            if (local == null) {
                return PlayHistoryEntity(
                    id = 0,
                    videoName = file.fileName(),
                    url = file.uniqueKey(),
                    mediaType = storage.library.mediaType,
                    videoPosition = remotePositionMs,
                    videoDuration = remoteDurationMs,
                    playTime = Date(remoteViewAtMs),
                    uniqueKey = file.uniqueKey(),
                    storagePath = file.storagePath(),
                    storageId = storage.library.id,
                )
            }

            val localViewAtMs = local.playTime.time
            val remoteNewer = remoteViewAtMs > localViewAtMs + remoteHistoryOverrideThresholdMs
            val positionDeltaMs = abs(remotePositionMs - local.videoPosition)
            val durationChanged = remoteDurationMs > 0 && remoteDurationMs != local.videoDuration
            val positionChanged = positionDeltaMs >= remoteHistoryPositionToleranceMs

            if (remoteNewer && (positionChanged || durationChanged)) {
                val updated =
                    local
                        .copy(
                            videoPosition = remotePositionMs,
                            videoDuration = if (remoteDurationMs > 0) remoteDurationMs else local.videoDuration,
                            playTime = Date(remoteViewAtMs),
                        ).also {
                            it.isLastPlay = local.isLastPlay
                        }
                DatabaseManager.instance.getPlayHistoryDao().insert(updated)
                history = updated
            }
        }

        return history
    }

    private fun normalizeRemoteProgress(
        progressSec: Long,
        durationSec: Long
    ): Long {
        if (progressSec <= 0) return 0
        if (durationSec > 0 && progressSec >= durationSec) return 0
        return progressSec.coerceAtLeast(0)
    }

    /**
     * 刷新最后一次播放记录
     */
    private suspend fun refreshStorageLastPlay() {
        storageLastPlay =
            DatabaseManager.instance
                .getPlayHistoryDao()
                .gitStorageLastPlay(storage.library.id)
        storageLastPlay?.isLastPlay = true
    }

    /**
     * 文件夹是否为最后一次播放记录的父文件夹
     * 是：返回最后播放的标签
     * 否：null
     */
    private fun lastPlayDirectoryHistory(file: StorageFile): PlayHistoryEntity? {
        val lastPlayStoragePath =
            storageLastPlay?.storagePath
                ?: return null
        if (TextUtils.isEmpty(lastPlayStoragePath)) {
            return null
        }
        if (file.isStoragePathParent(lastPlayStoragePath).not()) {
            return null
        }
        return lastPlayDirectory
    }

    /**
     * 是否可展示的文件
     */
    private fun isDisplayFile(storageFile: StorageFile): Boolean {
        // .开头的文件，根据配置展示
        if (hidePointFile && storageFile.fileName().startsWith(".")) {
            return false
        }
        // 文件夹，展示
        if (storageFile.isDirectory()) {
            return true
        }
        // 视频文件，展示
        return storageFile.isVideoFile()
    }

    private suspend fun getStorageFileHistory(storageFile: StorageFile): PlayHistoryEntity =
        DatabaseManager.instance.getPlayHistoryDao().getPlayHistory(
            storageFile.uniqueKey(),
            storageFile.storage.library.id,
        ) ?: PlayHistoryEntity(
            0,
            "",
            "",
            mediaType = storageFile.storage.library.mediaType,
            uniqueKey = storageFile.uniqueKey(),
            storageId = storageFile.storage.library.id,
        )

    private fun buildDisplayItems(files: List<StorageFile>): List<Any> {
        val items = files.toMutableList<Any>()
        val paged = storage as? PagedStorage
        if (paged != null && paged.shouldShowPagingItem(storage.directory)) {
            items.add(StoragePagingItem(paged.state, paged.hasMore(), files.isEmpty()))
        }
        return items
    }

    private fun handleLoginRequiredIfNeeded(
        throwable: Throwable,
        clearList: Boolean
    ): Boolean {
        val authStorage = storage as? AuthStorage ?: return false

        val message =
            when (throwable) {
                is BaiduPanReAuthRequiredException ->
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: "登录已失效，请重新${authStorage.loginActionText(storage.directory)}"
                is BilibiliException -> {
                    if (throwable.code != -101) return false
                    "登录已失效，请重新${authStorage.loginActionText(storage.directory)}"
                }
                else -> return false
            }

        ToastCenter.showWarning(message)
        _loginRequiredLiveData.postValue(storage.library)

        if (clearList) {
            val empty = emptyList<Any>()
            _fileLiveData.postValue(empty)
            filesSnapshot = empty
        }

        return true
    }
}
