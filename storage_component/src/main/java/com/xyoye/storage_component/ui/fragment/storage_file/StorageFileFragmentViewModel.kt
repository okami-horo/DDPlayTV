package com.xyoye.storage_component.ui.fragment.storage_file

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.database.repository.PlayHistoryRepository
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanReAuthRequiredException
import com.xyoye.common_component.storage.cloud115.auth.Cloud115ReAuthRequiredException
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.open115.auth.Open115ReAuthRequiredException
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.TrackType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

class StorageFileFragmentViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hidePointFiles: () -> Boolean = { !AppConfig.isShowHiddenFile() },
    private val historyLookup: suspend (StorageFile) -> PlayHistoryEntity? = { file ->
        PlayHistoryRepository.findOrClaimLegacyHistory(
            file.uniqueKey(),
            file.storage.library.id,
            file.storage.library.mediaType,
            file.fileUrl()
        )
    },
    private val lastPlayLookup: suspend (Int) -> PlayHistoryEntity? = { PlayHistoryRepository.getStorageLastPlay(it) },
    private val comparator: () -> Comparator<StorageFile> = { StorageSortOption.comparator() }
) : BaseViewModel() {
    private val _state = MutableLiveData(StorageBrowseState())
    val state: LiveData<StorageBrowseState> = _state
    private val _loginRequiredLiveData = MutableLiveData<MediaLibraryEntity?>()
    val loginRequiredLiveData: LiveData<MediaLibraryEntity?> = _loginRequiredLiveData
    private var boundStorage: Storage? = null
    var storage: Storage
        get() = requireNotNull(boundStorage)
        set(value) {
            val previous = boundStorage
            boundStorage = value
            if (previous != null && previous !== value) {
                requestVersion++
                requestJob?.cancel()
                operation = null
                searchResults = null
                pagingSnapshot = null
                _state.value = StorageBrowseState(query = current.query)
            }
        }
    var operationLock = Mutex()
    var isPageActive: () -> Boolean = { true }
    private var requestVersion = 0L
    private var requestJob: Job? = null
    private var operation: Operation? = null
    private var searchResults: List<StorageFile>? = null
    private var pagingSnapshot: PagedStorage.Snapshot? = null
    private val current get() = requireNotNull(_state.value)

    fun consumeLoginRequest() {
        _loginRequiredLiveData.value = null
    }

    fun cancelRequests() {
        requestVersion++
        requestJob?.cancel()
        operation = null
        publish(current)
    }

    fun listFile(
        directory: StorageFile?,
        refresh: Boolean = false,
        directoryPath: String? = null
    ) {
        request(Operation.DIRECTORY) { requestStorage ->
            val auth = requestStorage as? AuthStorage
            if (auth != null && auth.requiresLogin(directory) && !auth.isConnected()) {
                throw LoginRequiredException("请先" + auth.loginActionText(directory))
            }
            val result =
                withContext(ioDispatcher) {
                    val target =
                        directory ?: if (directoryPath != null && directoryPath != "/") {
                            requestStorage.pathFile(directoryPath, isDirectory = true)
                        } else {
                            requestStorage.getRootFile()
                        } ?: throw FileNotFoundException("媒体库目录不可用")
                    currentCoroutineContext().ensureActive()
                    if (auth != null && auth.requiresLogin(target) && !auth.isConnected()) {
                        throw LoginRequiredException("请先" + auth.loginActionText(target))
                    }
                    val files = requestStorage.openDirectory(target, refresh)
                    currentCoroutineContext().ensureActive()
                    val originals = annotateHistory(files, requestStorage)
                    target to originals
                }
            ensureRequest()
            searchResults = null
            pagingSnapshot = (requestStorage as? PagedStorage)?.snapshotPaging()
            val next = current.copy(directory = result.first, directoryFiles = result.second, hasDirectory = true)
            requestStorage.directory = result.first
            requestStorage.directoryFiles = result.second
            publish(next)
        }
    }

    fun changeSortOption() {
        _state.value = current.copy(items = displayItems(current))
    }

    fun searchByText(text: String) {
        searchResults = null
        val updated =
            if (current.hasDirectory) {
                current.copy(query = text, error = null, loginRequired = false)
            } else {
                current.copy(query = text)
            }
        _state.value = updated.copy(items = displayItems(updated))
        if (operation == Operation.DIRECTORY || !current.hasDirectory) return
        if (text.isEmpty() || !storage.supportSearch()) {
            if (operation == Operation.SEARCH) cancelRequests()
            return
        }
        request(Operation.SEARCH) { requestStorage ->
            val files = withContext(ioDispatcher) { annotateHistory(requestStorage.search(text), requestStorage) }
            ensureRequest()
            searchResults = files
            publish(current)
        }
    }

    /**
     * 绑定音频文件
     */
    fun bindAudioSource(
        file: StorageFile,
        audioPath: String
    ) {
        viewModelScope.launch {
            val playHistory = getStorageFileHistory(file)
            playHistory.audioPath = audioPath
            PlayHistoryRepository.insert(playHistory)

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
        viewModelScope.launch {
            when (resource) {
                TrackType.DANMU -> {
                    PlayHistoryRepository.updateDanmu(
                        file.uniqueKey(),
                        storage.library.id,
                        null,
                        null,
                    )
                }

                TrackType.SUBTITLE -> {
                    PlayHistoryRepository.updateSubtitle(
                        file.uniqueKey(),
                        storage.library.id,
                        null,
                    )
                }

                TrackType.AUDIO -> {
                    PlayHistoryRepository.updateAudio(
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

    fun updateHistory() {
        if (!current.hasDirectory) return
        if (current.query.isNotEmpty() && storage.supportSearch() && searchResults == null) {
            searchByText(current.query)
            return
        }
        request(Operation.HISTORY) { requestStorage ->
            val originals = current.directoryFiles
            val results = searchResults
            val updated =
                withContext(ioDispatcher) {
                    annotateHistory(originals, requestStorage) to results?.let { annotateHistory(it, requestStorage) }
                }
            ensureRequest()
            searchResults = updated.second
            requestStorage.directoryFiles = updated.first
            publish(current.copy(directoryFiles = updated.first))
        }
    }

    fun loadMore() {
        val paged = storage as? PagedStorage ?: return
        if (current.query.isNotEmpty() || current.loading || !current.hasDirectory) return
        if (pagingSnapshot?.hasMore == false) return
        request(Operation.PAGE) { requestStorage ->
            val appended =
                withContext(ioDispatcher) {
                    paged.loadMore().getOrThrow().let { annotateHistory(it, requestStorage) }
                }
            ensureRequest()
            val keys = current.directoryFiles.map { it.uniqueKey() }.toHashSet()
            val merged = current.directoryFiles + appended.filter { keys.add(it.uniqueKey()) }
            pagingSnapshot = paged.snapshotPaging()
            requestStorage.directoryFiles = merged
            publish(current.copy(directoryFiles = merged))
        }
    }

    private suspend fun ensureRequest() {
        currentCoroutineContext().ensureActive()
        if (!isPageActive()) throw CancellationException("Directory is no longer active")
    }

    private fun request(
        kind: Operation,
        action: suspend (Storage) -> Unit
    ) {
        val version = ++requestVersion
        val requestStorage = storage
        requestJob?.cancel()
        operation = kind
        val loading = current.copy(loading = true, error = null, loginRequired = false)
        _state.value = loading.copy(items = displayItems(loading))
        requestJob =
            viewModelScope.launch {
                try {
                    operationLock.withLock {
                        if (version != requestVersion || requestStorage !== storage || !isPageActive()) return@withLock
                        val previousDirectory = requestStorage.directory
                        val previousFiles = requestStorage.directoryFiles
                        val previousPaging = (requestStorage as? PagedStorage)?.snapshotPaging()
                        var accepted = false
                        try {
                            if (current.hasDirectory) {
                                requestStorage.directory = current.directory
                                requestStorage.directoryFiles = current.directoryFiles
                                pagingSnapshot?.let { (requestStorage as? PagedStorage)?.restorePaging(it) }
                            }
                            action(requestStorage)
                            ensureRequest()
                            accepted = version == requestVersion
                        } finally {
                            if (!accepted) {
                                requestStorage.directory = previousDirectory
                                requestStorage.directoryFiles = previousFiles
                                previousPaging?.let { (requestStorage as? PagedStorage)?.restorePaging(it) }
                            }
                        }
                    }
                    if (kind == Operation.DIRECTORY &&
                        version == requestVersion &&
                        isPageActive() &&
                        current.query.isNotEmpty() &&
                        storage.supportSearch()
                    ) {
                        operation = null
                        searchByText(current.query)
                    }
                } catch (cancelled: CancellationException) {
                    if (version == requestVersion && isPageActive()) publish(current)
                    throw cancelled
                } catch (failure: Exception) {
                    if (version != requestVersion || !isPageActive()) return@launch
                    if (kind == Operation.PAGE) pagingSnapshot = pagingSnapshot?.copy(state = PagedStorage.State.ERROR)
                    val loginRequired =
                        failure is LoginRequiredException ||
                            failure is BaiduPanReAuthRequiredException ||
                            failure is Open115ReAuthRequiredException ||
                            failure is Cloud115ReAuthRequiredException
                    publish(current.copy(error = failure.message ?: "加载失败，请重试", loginRequired = loginRequired))
                    if (loginRequired) _loginRequiredLiveData.value = storage.library
                } finally {
                    if (version == requestVersion) operation = null
                }
            }
    }

    private fun publish(value: StorageBrowseState) {
        val settled = value.copy(loading = false)
        _state.value = settled.copy(items = displayItems(settled))
    }

    private fun displayItems(value: StorageBrowseState): List<Any> {
        val files = if (value.query.isNotEmpty() && storage.supportSearch()) searchResults.orEmpty() else value.directoryFiles
        val displayed =
            files
                .filter { isDisplayFile(it) }
                .filter { value.query.isEmpty() || storage.supportSearch() || it.fileName().contains(value.query) }
                .sortedWith(comparator())
        val items = displayed.toMutableList<Any>()
        val paged = storage as? PagedStorage
        val paging = pagingSnapshot
        if (value.query.isEmpty() && paged != null && paging != null && paged.shouldShowPagingItem(value.directory)) {
            val pagingState = if (value.loading) PagedStorage.State.LOADING else paging.state
            items.add(StoragePagingItem(pagingState, paging.hasMore, displayed.isEmpty()))
        }
        return items
    }

    private suspend fun annotateHistory(
        files: List<StorageFile>,
        requestStorage: Storage
    ): List<StorageFile> {
        val lastPlay = lastPlayLookup(requestStorage.library.id)
        return files.map { file ->
            val history =
                when {
                    file.isDirectory() ->
                        lastPlay
                            ?.takeIf {
                                !it.storagePath.isNullOrEmpty() && file.isStoragePathParent(it.storagePath!!)
                            }?.copy()
                            ?.apply { isLastPlay = true }
                    file.isVideoFile() -> historyLookup(file)?.copy()?.apply { isLastPlay = id == lastPlay?.id }
                    else -> null
                }
            file.clone().apply { playHistory = history }
        }
    }

    private fun isDisplayFile(file: StorageFile): Boolean =
        !(hidePointFiles() && file.fileName().startsWith(".")) && (file.isDirectory() || file.isVideoFile())

    private suspend fun getStorageFileHistory(file: StorageFile): PlayHistoryEntity =
        historyLookup(file) ?: PlayHistoryEntity(
            videoName = "",
            url = "",
            mediaType = file.storage.library.mediaType,
            uniqueKey = file.uniqueKey(),
            storageId = file.storage.library.id
        )

    private class LoginRequiredException(
        message: String
    ) : IllegalStateException(message)

    private enum class Operation { DIRECTORY, SEARCH, HISTORY, PAGE }
}
