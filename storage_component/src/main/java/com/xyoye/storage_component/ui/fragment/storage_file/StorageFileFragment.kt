package com.xyoye.storage_component.ui.fragment.storage_file

import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.FocusTarget
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.toResString
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.focus.RecyclerViewFocusDelegate
import com.xyoye.common_component.focus.setDescendantFocusBlocked
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.BilibiliStorage
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.FragmentStorageFileBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.dialog.BaiduPanLoginDialog
import com.xyoye.storage_component.ui.dialog.BilibiliLoginDialog

class StorageFileFragment : BaseFragment<StorageFileFragmentViewModel, FragmentStorageFileBinding>() {
    private val directory: StorageFile? by lazy { ownerActivity.directory }
    private var tvHistoryHintShown = false
    private var lastPagingHintState: com.xyoye.common_component.storage.PagedStorage.State? = null
    private val focusDelegate by lazy(LazyThreadSafetyMode.NONE) {
        RecyclerViewFocusDelegate(
            recyclerView = dataBinding.storageFileRv,
            uniqueKeyProvider = { item -> (item as? StorageFile)?.uniqueKey() },
        )
    }

    companion object {
        private const val TAG = "StorageFileFocus"

        fun newInstance() = StorageFileFragment()
    }

    private val ownerActivity by lazy {
        requireActivity() as StorageFileActivity
    }

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            StorageFileFragmentViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.fragment_storage_file

    override fun initView() {
        initRecyclerView()

        viewModel.storage = ownerActivity.storage

        dataBinding.refreshLayout.setOnRefreshListener {
            reloadDirectory(refresh = true)
        }

        viewModel.fileLiveData.observe(this) {
            dataBinding.loading.isVisible = false
            dataBinding.refreshLayout.isVisible = true
            dataBinding.refreshLayout.isRefreshing = false
            ownerActivity.onDirectoryOpened(this, it.filterIsInstance<StorageFile>())
            dataBinding.storageFileRv.setData(it)

            if (!dataBinding.storageFileRv.isInTouchMode) {
                val pagingItem = it.lastOrNull() as? StoragePagingItem
                if (pagingItem != null) {
                    if (!tvHistoryHintShown &&
                        ownerActivity.storage.library.mediaType == MediaType.BILIBILI_STORAGE &&
                        BilibiliStorage.isBilibiliPagedDirectoryPath(ownerActivity.directory?.filePath())
                    ) {
                        ToastCenter.showInfo("提示：按菜单键/设置键可刷新，列表底部可选择“加载更多/重试/刷新”")
                        tvHistoryHintShown = true
                    }

                    if (pagingItem.state != lastPagingHintState) {
                        lastPagingHintState = pagingItem.state
                        if (pagingItem.state == com.xyoye.common_component.storage.PagedStorage.State.ERROR) {
                            ToastCenter.showError("加载失败，按确认键重试")
                        }
                    }
                }
            }
            // ownerActivity.onDirectoryDataLoaded(this, it)
            // 仅在需要恢复上次焦点时，才请求焦点（与媒体库首页保持一致：首次进入不默认高亮首项）
            if (!dataBinding.storageFileRv.isInTouchMode &&
                focusDelegate.pendingFocusIndex == RecyclerView.NO_POSITION &&
                focusDelegate.pendingFocusUniqueKey == null
            ) {
                val showEmptyActionFocus =
                    it.isEmpty() ||
                        ((it.singleOrNull() as? StoragePagingItem)?.isDataEmpty == true)
                if (showEmptyActionFocus) {
                    focusDelegate.setPendingFocus(index = 0)
                }
            }

            if (focusDelegate.pendingFocusIndex != RecyclerView.NO_POSITION || focusDelegate.pendingFocusUniqueKey != null) {
                // 触控模式下不恢复列表焦点，避免出现“默认高亮/焦点框”
                if (dataBinding.storageFileRv.isInTouchMode) {
                    focusDelegate.clearPendingFocus()
                    return@observe
                }

                val adapterCount = dataBinding.storageFileRv.adapter?.itemCount ?: 0
                if (adapterCount <= 0) {
                    LogFacade.d(LogModule.STORAGE, TAG, "pending focus skipped, adapter empty")
                    return@observe
                }

                val resolvedIndex = focusDelegate.resolvePendingFocusIndex(it, adapterCount)
                focusDelegate.setPendingFocus(index = resolvedIndex)
                // 等待下一次消息循环后再请求焦点，避免因子 View 未就绪导致的滚动跳变
                dataBinding.storageFileRv.post { requestFocus() }
            }
        }

        viewModel.loginRequiredLiveData.observe(this) { library ->
            showLoginDialog(library)
        }

        viewModel.listFile(directory)
    }

    override fun onResume() {
        super.onResume()
        (bindingOrNull?.root as? ViewGroup)?.setDescendantFocusBlocked(false)
        viewModel.updateHistory()
        focusDelegate.onResume()
    }

    override fun onPause() {
        focusDelegate.onPause()
        super.onPause()
        (bindingOrNull?.root as? ViewGroup)?.setDescendantFocusBlocked(true)
    }

    private fun initRecyclerView() {
        dataBinding.storageFileRv.apply {
            layoutManager = vertical()

            adapter =
                StorageFileAdapter(
                    activity = ownerActivity,
                    viewModel = viewModel,
                    onRefreshRequested = { triggerTvRefresh() },
                    onLoginRequested = { showLoginDialog(ownerActivity.storage.library) },
                ).create()

            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(
                        recyclerView: RecyclerView,
                        dx: Int,
                        dy: Int
                    ) {
                        if (dy <= 0) return
                        if (!recyclerView.isInTouchMode) return
                        val paged = viewModel.storage as? com.xyoye.common_component.storage.PagedStorage ?: return
                        if (paged.state != com.xyoye.common_component.storage.PagedStorage.State.IDLE) return
                        if (!paged.hasMore()) return
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val itemCount = recyclerView.adapter?.itemCount ?: return
                        if (itemCount <= 0) return
                        val lastVisible = lm.findLastVisibleItemPosition()
                        if (lastVisible >= itemCount - 2) {
                            viewModel.loadMore()
                        }
                    }
                },
            )

            focusDelegate.installVerticalDpadKeyNavigation(
                focusTargetProvider = { rv -> rv.resolveVerticalMoveFocusTarget() },
                onMenuKeyDown = { triggerTvRefresh() },
                onSettingsKeyDown = { triggerTvRefresh() },
                consumeDownKeyWhenBottom = true
            )
        }
    }

    private fun RecyclerView.resolveVerticalMoveFocusTarget(): FocusTarget {
        val focusedView = findFocus()
        val isMoreActionFocused = focusedView?.id == com.xyoye.core_ui_component.R.id.more_action_iv
        return if (isMoreActionFocused) {
            FocusTarget.ViewId(com.xyoye.core_ui_component.R.id.more_action_iv)
        } else {
            FocusTarget.Tag(R.string.focusable_item.toResString())
        }
    }

    fun triggerTvRefresh() {
        val binding = bindingOrNull ?: return
        val recyclerView = binding.storageFileRv
        if (recyclerView.isInTouchMode) {
            reloadDirectory(refresh = true)
            return
        }

        val isBilibiliHistory =
            ownerActivity.storage.library.mediaType == MediaType.BILIBILI_STORAGE &&
                directory?.filePath() == com.xyoye.common_component.storage.impl.BilibiliStorage.PATH_HISTORY_DIR

        if (isBilibiliHistory) {
            // 历史列表刷新：刷新后聚焦第一项（最新）
            focusDelegate.setPendingFocus(index = 0)
        } else {
            val (focusedIndex, focusedUniqueKey) = focusDelegate.captureFocusedItem()
            focusDelegate.setPendingFocus(
                index =
                    when {
                        focusedIndex != RecyclerView.NO_POSITION -> focusedIndex
                        else -> 0
                    },
                uniqueKey = focusedUniqueKey,
            )
        }

        ToastCenter.showInfo("刷新中…")
        reloadDirectory(refresh = true)
    }

    fun reloadDirectory(refresh: Boolean = false) {
        if (refresh) {
            dataBinding.refreshLayout.isRefreshing = true
        }
        viewModel.listFile(directory, refresh)
    }

    fun requestFocus(reversed: Boolean = false) {
        val binding = bindingOrNull ?: return
        if (binding.storageFileRv.isInTouchMode) {
            LogFacade.d(LogModule.STORAGE, TAG, "requestFocus skipped (touch mode)")
            return
        }
        val moved = focusDelegate.requestFocus(reversed = reversed)
        LogFacade.d(LogModule.STORAGE, TAG, "requestFocus reversed=$reversed moved=$moved")
    }

    fun focusFile(uniqueKey: String) {
        val binding = bindingOrNull ?: return
        val adapter = binding.storageFileRv.adapter as? BaseAdapter ?: return
        val targetIndex =
            adapter.items.indexOfFirst { item ->
                val storageFile = item as? StorageFile ?: return@indexOfFirst false
                storageFile.playHistory?.uniqueKey == uniqueKey
            }
        if (targetIndex == -1) {
            LogFacade.w(LogModule.STORAGE, TAG, "focusFile target not found uniqueKey=$uniqueKey")
            return
        }
        focusDelegate.setPendingFocus(index = targetIndex)
        binding.storageFileRv.post { requestFocus() }
    }

    /**
     * 搜索
     */
    fun search(text: String) {
        viewModel.searchByText(text)
    }

    /**
     * 修改文件排序
     */
    fun sort() {
        viewModel.changeSortOption()
    }

    private fun showLoginDialog(library: MediaLibraryEntity) {
        when (library.mediaType) {
            MediaType.BILIBILI_STORAGE -> showBilibiliLoginDialog(library)
            MediaType.BAIDU_PAN_STORAGE -> showBaiduPanLoginDialog(library)
            MediaType.OPEN_115_STORAGE -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Stream.StoragePlus)
                    .withSerializable("mediaType", library.mediaType)
                    .withParcelable("editData", library)
                    .navigation(ownerActivity, StorageFileActivity.REQUEST_CODE_OPEN115_REAUTH)
            }
            else -> Unit
        }
    }

    private fun showBilibiliLoginDialog(library: MediaLibraryEntity) {
        BilibiliLoginDialog(
            activity = ownerActivity,
            library = library,
            onLoginSuccess = { triggerTvRefresh() },
            onDismiss = {
                val authStorage = ownerActivity.storage as? AuthStorage
                val requiresLogin =
                    authStorage?.let { it.requiresLogin(directory) && !it.isConnected() } == true
                bindingOrNull?.let { binding ->
                    if (!binding.storageFileRv.isInTouchMode && requiresLogin) {
                        focusDelegate.setPendingFocus(index = 0)
                        binding.storageFileRv.post { requestFocus() }
                    }
                }
            },
        ).show()
    }

    private fun showBaiduPanLoginDialog(library: MediaLibraryEntity) {
        BaiduPanLoginDialog(
            activity = ownerActivity,
            onLoginSuccess = { token, uinfo ->
                val uk = uinfo.uk ?: 0L
                if (uk <= 0L) {
                    ToastCenter.showError("授权失败：uk 无效")
                    return@BaiduPanLoginDialog
                }

                val expectedUk =
                    library.url
                        .substringAfter("baidupan://uk/", missingDelimiterValue = "")
                        .toLongOrNull()
                        ?.takeIf { it > 0L }

                if (expectedUk != null && expectedUk != uk) {
                    ToastCenter.showError("授权账号与当前存储源不一致，请在编辑存储源中重新绑定")
                    return@BaiduPanLoginDialog
                }

                val nowMs = System.currentTimeMillis()
                val expiresAtMs = nowMs + token.expiresIn * 1000L
                val storageKey = BaiduPanAuthStore.storageKey(library)

                BaiduPanAuthStore.writeTokens(
                    storageKey = storageKey,
                    accessToken = token.accessToken,
                    expiresAtMs = expiresAtMs,
                    refreshToken = token.refreshToken,
                    scope = token.scope,
                    updatedAtMs = nowMs
                )
                BaiduPanAuthStore.writeProfile(
                    storageKey = storageKey,
                    uk = uk,
                    netdiskName = uinfo.netdiskName,
                    avatarUrl = uinfo.avatarUrl,
                    updatedAtMs = nowMs
                )

                ToastCenter.showOriginalToast("授权成功")
                triggerTvRefresh()
            },
            onDismiss = {
                val authStorage = ownerActivity.storage as? AuthStorage
                val requiresLogin =
                    authStorage?.let { it.requiresLogin(directory) && !it.isConnected() } == true
                bindingOrNull?.let { binding ->
                    if (!binding.storageFileRv.isInTouchMode && requiresLogin) {
                        focusDelegate.setPendingFocus(index = 0)
                        binding.storageFileRv.post { requestFocus() }
                    }
                }
            },
        ).show()
    }
}
