package com.xyoye.user_component.ui.fragment.personal

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.data_component.data.CloudHistoryListData
import com.xyoye.data_component.data.FollowAnimeData
import com.xyoye.data_component.data.LoginData
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2020/7/28.
 */

class PersonalFragmentViewModel(
    private val relationRequest: suspend () -> Pair<Int, Int> = {
        val followedSize =
            AnimeRepository
                .getFollowedAnime()
                .getOrNull()
                ?.favorites
                ?.size ?: 0
        val historySize =
            AnimeRepository
                .getPlayHistory()
                .getOrNull()
                ?.playHistoryAnimes
                ?.size ?: 0
        followedSize to historySize
    }
) : BaseViewModel() {
    var followData: FollowAnimeData? = null
    var historyData: CloudHistoryListData? = null

    val relationLiveData = MutableLiveData<Pair<Int, Int>>()
    private var account: String? = null
    private var requestVersion = 0L
    private var relationJob: Job? = null

    fun onLoginChanged(loginData: LoginData?) {
        val currentAccount = loginData?.userName ?: loginData?.token
        if (account == currentAccount) return
        account = currentAccount
        val version = ++requestVersion
        relationJob?.cancel()
        if (currentAccount == null) {
            followData = null
            historyData = null
            relationLiveData.value = 0 to 0
            return
        }
        relationJob =
            viewModelScope.launch {
                val relation = relationRequest()
                currentCoroutineContext().ensureActive()
                if (requestVersion == version) relationLiveData.value = relation
            }
    }
}
