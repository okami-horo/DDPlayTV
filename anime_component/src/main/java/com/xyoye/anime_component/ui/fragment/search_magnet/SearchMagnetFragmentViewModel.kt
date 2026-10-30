package com.xyoye.anime_component.ui.fragment.search_magnet

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.database.repository.MagnetSearchHistoryRepository
import com.xyoye.common_component.network.repository.MagnetRepository
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.MagnetData
import com.xyoye.data_component.data.MagnetResourceData
import com.xyoye.data_component.data.MagnetSubgroupData
import com.xyoye.data_component.data.MagnetTypeData
import com.xyoye.data_component.entity.MagnetScreenEntity
import com.xyoye.data_component.enums.MagnetScreenType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SearchMagnetFragmentViewModel(
    private val searchRequest: suspend (String, String, String, String) -> Result<MagnetResourceData> = MagnetRepository::searchMagnet,
    private val typeRequest: suspend (String) -> Result<MagnetTypeData> = MagnetRepository::getMagnetType,
    private val subgroupRequest: suspend (String) -> Result<MagnetSubgroupData> = MagnetRepository::getMagnetSubgroup,
    private val recordSearch: suspend (String) -> Unit = MagnetSearchHistoryRepository::insert,
    private val domain: () -> String? = { AppConfig.getMagnetResDomain() }
) : BaseViewModel() {
    data class QueryState(
        val keyword: String = "",
        val domain: String? = null,
        val typeId: String = "",
        val subgroupId: String = "",
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val items: List<MagnetData> = emptyList(),
        val error: String? = null,
        val filterLoading: Boolean = false,
        val filterError: String? = null
    )

    sealed class DialogRequest {
        abstract val revision: Long

        data class Filter(
            override val revision: Long,
            val domain: String,
            val type: MagnetScreenType,
            val items: List<MagnetScreenEntity>
        ) : DialogRequest()

        data class Domain(
            override val revision: Long
        ) : DialogRequest()
    }

    val magnetTypeId = ObservableField<Int>()
    val magnetTypeText = ObservableField("全部分类")
    val magnetSubgroupId = ObservableField<Int>()
    val magnetSubgroupText = ObservableField("全部字幕组")
    val searchText = ObservableField<String>()
    val searchHistoryLiveData by lazy { MagnetSearchHistoryRepository.getAll() }

    private val _queryState = MutableStateFlow(QueryState())
    val queryState = _queryState.asStateFlow()
    private var queryVersion = 0L
    private var queryJob: Job? = null
    private var initialSearchHandled = false
    private var dialogVersion = 0L
    private var filterJob: Job? = null
    private var lastFilter: MagnetScreenType? = null
    private val filterCache = mutableMapOf<Pair<String, MagnetScreenType>, List<MagnetScreenEntity>>()
    private val dialogs = Channel<DialogRequest>(Channel.CONFLATED)
    val dialogRequests = dialogs.receiveAsFlow()

    fun initializeSearch(keyword: String?): Boolean {
        if (initialSearchHandled) return false
        initialSearchHandled = true
        if (!keyword.isNullOrEmpty()) {
            searchText.set(keyword)
            search()
            return true
        }
        return false
    }

    fun search() {
        val keyword = searchText.get().orEmpty()
        if (keyword.isEmpty()) {
            clearSearch()
            ToastCenter.showWarning("请输入搜索条件")
            return
        }
        val magnetDomain = domain()
        val version = ++queryVersion
        queryJob?.cancel()
        if (magnetDomain.isNullOrEmpty()) {
            _queryState.value = QueryState(keyword = keyword)
            requestDomain()
            return
        }
        val typeId = magnetTypeId.get()?.toString().orEmpty()
        val subgroupId = magnetSubgroupId.get()?.toString().orEmpty()
        val previous = _queryState.value
        val sameQuery =
            previous.keyword == keyword &&
                previous.domain == magnetDomain &&
                previous.typeId == typeId &&
                previous.subgroupId == subgroupId
        _queryState.value =
            if (sameQuery) {
                previous.copy(loading = true, error = null, filterError = null)
            } else {
                previous.copy(
                    keyword = keyword,
                    domain = magnetDomain,
                    typeId = typeId,
                    subgroupId = subgroupId,
                    loading = true,
                    loaded = false,
                    items = emptyList(),
                    error = null,
                    filterError = null
                )
            }
        queryJob =
            viewModelScope.launch {
                try {
                    recordSearch(keyword)
                    val data = searchRequest(magnetDomain, keyword, typeId, subgroupId).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (version != queryVersion || domain() != magnetDomain) return@launch
                    _queryState.value = _queryState.value.copy(items = data.Resources?.toList().orEmpty(), loaded = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (version == queryVersion && domain() == magnetDomain) {
                        _queryState.value = _queryState.value.copy(error = failure.message ?: "搜索失败，请重试")
                    }
                } finally {
                    if (version == queryVersion) _queryState.value = _queryState.value.copy(loading = false)
                }
            }
    }

    fun clearSearch() {
        ++queryVersion
        queryJob?.cancel()
        searchText.set("")
        _queryState.value = _queryState.value.copy(keyword = "", loading = false, loaded = false, items = emptyList(), error = null)
    }

    fun retry() {
        val filter = lastFilter
        if (_queryState.value.filterError != null && filter != null) requestFilter(filter) else search()
    }

    fun deleteSearchHistory(searchText: String) {
        viewModelScope.launch { MagnetSearchHistoryRepository.deleteByText(searchText) }
    }

    fun deleteAllSearchHistory() {
        viewModelScope.launch { MagnetSearchHistoryRepository.deleteAll() }
    }

    fun getMagnetSubgroup() = requestFilter(MagnetScreenType.SUBGROUP)

    fun getMagnetType() = requestFilter(MagnetScreenType.TYPE)

    fun isCurrentDialog(request: DialogRequest): Boolean =
        request.revision == dialogVersion &&
            when (request) {
                is DialogRequest.Domain -> domain().isNullOrEmpty()
                is DialogRequest.Filter -> request.domain == domain()
            }

    fun onDomainChanged() {
        ++queryVersion
        ++dialogVersion
        queryJob?.cancel()
        filterJob?.cancel()
        dialogs.tryReceive()
        filterCache.clear()
        magnetTypeId.set(null)
        magnetTypeText.set("全部分类")
        magnetSubgroupId.set(null)
        magnetSubgroupText.set("全部字幕组")
        _queryState.value = QueryState(keyword = searchText.get().orEmpty())
    }

    fun cancelDialogRequest() {
        ++dialogVersion
        filterJob?.cancel()
        dialogs.tryReceive()
        _queryState.value = _queryState.value.copy(filterLoading = false, filterError = null)
    }

    private fun requestDomain() {
        val revision = ++dialogVersion
        filterJob?.cancel()
        _queryState.value = _queryState.value.copy(filterLoading = false, filterError = null)
        dialogs.trySend(DialogRequest.Domain(revision))
    }

    private fun requestFilter(type: MagnetScreenType) {
        lastFilter = type
        val magnetDomain = domain()
        if (magnetDomain.isNullOrEmpty()) {
            requestDomain()
            return
        }
        val revision = ++dialogVersion
        filterJob?.cancel()
        dialogs.tryReceive()
        val key = magnetDomain to type
        _queryState.value = _queryState.value.copy(filterLoading = false, filterError = null)
        filterCache[key]?.let {
            dialogs.trySend(DialogRequest.Filter(revision, magnetDomain, type, it))
            return
        }
        _queryState.value = _queryState.value.copy(filterLoading = true)
        filterJob =
            viewModelScope.launch {
                try {
                    val items =
                        if (type == MagnetScreenType.TYPE) {
                            typeRequest(magnetDomain).getOrThrow().Types.map { MagnetScreenEntity(it.Id, it.Name, type) }
                        } else {
                            subgroupRequest(magnetDomain).getOrThrow().Subgroups.map { MagnetScreenEntity(it.Id, it.Name, type) }
                        }
                    currentCoroutineContext().ensureActive()
                    if (revision != dialogVersion || domain() != magnetDomain) return@launch
                    filterCache[key] = items
                    dialogs.trySend(DialogRequest.Filter(revision, magnetDomain, type, items))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (revision == dialogVersion && domain() == magnetDomain) {
                        _queryState.value = _queryState.value.copy(filterError = failure.message ?: "筛选加载失败，请重试")
                    }
                } finally {
                    if (revision == dialogVersion) _queryState.value = _queryState.value.copy(filterLoading = false)
                }
            }
    }
}
