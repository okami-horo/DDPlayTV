package com.xyoye.anime_component.ui.fragment.search_anime

import androidx.databinding.ObservableField
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.anime_component.R
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.repository.AnimeSearchHistoryRepository
import com.xyoye.common_component.extension.toResString
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.common_component.session.UserSessionManager
import com.xyoye.common_component.utils.stringCompare
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.CommonTypeData
import com.xyoye.data_component.data.SearchAnimeData
import com.xyoye.data_component.enums.AnimeSortType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

class SearchAnimeFragmentViewModel(
    private val searchRequest: suspend (String, String?) -> Result<SearchAnimeData> = AnimeRepository::searchAnime,
    private val recordSearch: suspend (String) -> Unit = AnimeSearchHistoryRepository::insert
) : BaseViewModel() {
    data class QueryState(
        val keyword: String = "",
        val type: String? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val items: List<AnimeData> = emptyList(),
        val error: String? = null
    )

    private val _queryState = MutableStateFlow(QueryState())
    val queryState = _queryState.asStateFlow()
    private var queryVersion = 0L
    private var queryJob: Job? = null
    private val animeTypeData =
        mutableListOf(
            CommonTypeData("TV动画", "tvseries"),
            CommonTypeData("剧场版", "movie"),
            CommonTypeData("OVA", "ova"),
            CommonTypeData("日 剧", "jpdrama"),
            CommonTypeData("日本电影", "jpmovie"),
            CommonTypeData("网络放送", "web"),
            CommonTypeData("TV特送", "tvspecial"),
            CommonTypeData("未知分类", "unknown"),
            CommonTypeData("MV", "musicvideo"),
            CommonTypeData("其 它", "other"),
        )

    private val sortTypeData =
        mutableListOf(
            CommonTypeData("上映日期", AnimeSortType.DATE.value),
            CommonTypeData("名称", AnimeSortType.NAME.value),
            CommonTypeData("评分", AnimeSortType.RATING.value),
            CommonTypeData("关注", AnimeSortType.FOLLOW.value),
        )

    val screenSpanCount = 4

    private var sortType = AnimeSortType.NONE
    private var searchType: String? = null
    private lateinit var searchAnimeData: SearchAnimeData

    val searchText = ObservableField<String>()
    val isTypeExpanded = ObservableField(false)
    val isCheckedType = ObservableField(false)
    val checkedAnimeType = ObservableField("类型: ")

    val animeTypeLiveData = MutableLiveData<MutableList<CommonTypeData>>()
    val animeSortLiveData = MutableLiveData<MutableList<CommonTypeData>>()
    val searchHistoryLiveData by lazy { AnimeSearchHistoryRepository.getAll() }

    fun search() {
        val searchWord = searchText.get() ?: ""
        if (searchWord.length < 2) {
            clearSearch()
            ToastCenter.showWarning("关键字太短，长度至少为2")
            return
        }
        val type = searchType
        val version = ++queryVersion
        queryJob?.cancel()
        val previous = _queryState.value
        _queryState.value =
            if (previous.keyword == searchWord && previous.type == type) {
                previous.copy(loading = true, error = null)
            } else {
                QueryState(keyword = searchWord, type = type, loading = true)
            }
        queryJob =
            viewModelScope.launch {
                try {
                    recordSearch(searchWord)
                    val data = searchRequest(searchWord, type).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (version != queryVersion) return@launch
                    searchAnimeData = data
                    showSearchResult()
                    _queryState.value = _queryState.value.copy(loaded = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (version == queryVersion) {
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
        _queryState.value = QueryState()
    }

    fun toggleExpand() {
        val isExpanded = isTypeExpanded.get() ?: false
        isTypeExpanded.set(!isExpanded)
        getAnimeType()
    }

    fun getAnimeType() {
        animeTypeLiveData.postValue(
            if (isTypeExpanded.get() == false && animeTypeData.size > screenSpanCount) {
                animeTypeData.subList(0, screenSpanCount)
            } else {
                animeTypeData
            },
        )
    }

    fun getAnimeSort() {
        animeSortLiveData.postValue(sortTypeData)
    }

    fun checkType(position: Int) {
        var checkedIndex = -1
        for (index in animeTypeData.indices) {
            if (animeTypeData[index].isChecked) {
                checkedIndex = index
                break
            }
        }

        when (checkedIndex) {
            -1 -> {
                animeTypeData[position].isChecked = true
                searchType = animeTypeData[position].typeId
                checkedAnimeType.set(animeTypeData[position].typeName)
                isCheckedType.set(true)
            }

            position -> {
                searchType = null
                animeTypeData[position].isChecked = false
                checkedAnimeType.set("类型: ")
                isCheckedType.set(false)
            }

            else -> {
                animeTypeData[position].isChecked = true
                searchType = animeTypeData[position].typeId
                checkedAnimeType.set(animeTypeData[position].typeName)
                isCheckedType.set(true)

                animeTypeData[checkedIndex].isChecked = false
            }
        }
        getAnimeType()
        if (!searchText.get().isNullOrEmpty() && searchText.get()!!.length > 1) {
            search()
        }
    }

    fun checkSort(position: Int) {
        if (!_queryState.value.loaded) {
            return
        }

        if (!UserSessionManager.isLoggedIn() &&
            AnimeSortType.formValue(sortTypeData[position].typeId) == AnimeSortType.FOLLOW
        ) {
            ToastCenter.showWarning(R.string.tips_login_required.toResString())
            return
        }

        var checkedIndex = -1
        for (index in sortTypeData.indices) {
            if (sortTypeData[index].isChecked) {
                checkedIndex = index
                break
            }
        }

        when (checkedIndex) {
            -1 -> {
                sortTypeData[position].isChecked = true
                sortType = AnimeSortType.formValue(sortTypeData[position].typeId)
            }

            position -> {
                sortTypeData[position].isChecked = false
                sortType = AnimeSortType.NONE
            }

            else -> {
                sortTypeData[position].isChecked = true
                sortType = AnimeSortType.formValue(sortTypeData[position].typeId)

                sortTypeData[checkedIndex].isChecked = false
            }
        }
        getAnimeSort()
        showSearchResult()
    }

    fun deleteSearchHistory(searchText: String) {
        viewModelScope.launch {
            AnimeSearchHistoryRepository.deleteByText(searchText)
        }
    }

    fun deleteAllSearchHistory() {
        viewModelScope.launch {
            AnimeSearchHistoryRepository.deleteAll()
        }
    }

    private fun showSearchResult() {
        if (sortType == AnimeSortType.NONE) {
            _queryState.value = _queryState.value.copy(items = searchAnimeData.animes.toList())
            return
        }
        if (!UserSessionManager.isLoggedIn() && sortType == AnimeSortType.FOLLOW) {
            _queryState.value = _queryState.value.copy(items = searchAnimeData.animes.toList())
            return
        }
        if (this::searchAnimeData.isInitialized) {
            val sortedList =
                mutableListOf<AnimeData>().also {
                    it.addAll(searchAnimeData.animes)
                }
            Collections.sort(
                sortedList,
                kotlin.Comparator { o1, o2 ->
                    return@Comparator when (sortType) {
                        AnimeSortType.FOLLOW -> o1.isFavorited.compareTo(o2.isFavorited)

                        AnimeSortType.RATING -> o1.rating.compareTo(o2.rating)

                        AnimeSortType.DATE -> stringCompare(o2.startDate, o1.startDate)

                        AnimeSortType.NAME -> stringCompare(o1.animeTitle, o2.animeTitle)

                        else -> 0
                    }
                },
            )
            _queryState.value = _queryState.value.copy(items = sortedList)
        }
    }
}
