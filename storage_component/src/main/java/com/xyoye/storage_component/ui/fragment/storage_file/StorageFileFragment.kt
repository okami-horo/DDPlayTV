package com.xyoye.storage_component.ui.fragment.storage_file

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.arouter.launcher.ARouter
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.rubensousa.dpadrecyclerview.state.DpadStateRegistry
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.FragmentStorageFileBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.dialog.BaiduPanLoginDialog

class StorageFileFragment :
    BaseFragment<StorageFileFragmentViewModel, FragmentStorageFileBinding>(),
    DpadFocusOwner {
    private val directory: StorageFile? get() = ownerActivity.directoryFor(this)
    private var loaded = false
    private val listState by lazy { DpadStateRegistry(this) }
    private var restoreLayout = true
    private var savedFileKey: String? = null
    private val listKey get() = "${ownerActivity.storage.library.id}|${arguments?.getString("directory_path")}"
    private var lastPagingHintState: com.xyoye.common_component.storage.PagedStorage.State? = null
    private var lastError: String? = null

    companion object {
        private const val TAG = "StorageFileFocus"

        fun newInstance(path: String) =
            StorageFileFragment().apply {
                arguments = Bundle().apply { putString("directory_path", path) }
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedFileKey = savedInstanceState?.getString("focused_file")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberFile()
        (bindingOrNull?.storageFileRv as? DpadRecyclerView)?.let {
            listState.getScrollState().save(it, listKey, detachAdapter = false)
        }
        outState.putString("focused_file", savedFileKey)
        super.onSaveInstanceState(outState)
    }

    private fun rememberFile() {
        val list = bindingOrNull?.storageFileRv as? DpadRecyclerView ?: return
        savedFileKey = ((list.adapter as? BaseAdapter)?.items?.getOrNull(list.getSelectedPosition()) as? StorageFile)?.uniqueKey()
    }

    override fun onDestroyView() {
        rememberFile()
        (bindingOrNull?.storageFileRv as? DpadRecyclerView)?.let {
            listState.getScrollState().save(it, listKey)
        }
        super.onDestroyView()
    }

    override fun initView() {
        restoreLayout = true
        initRecyclerView()

        viewModel.storage = ownerActivity.storage
        viewModel.operationLock = ownerActivity.storageOperations
        viewModel.isPageActive = { ownerActivity.isCurrentDirectory(this) }

        dataBinding.refreshLayout.setOnRefreshListener {
            reloadDirectory(refresh = true)
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            val it = state.items
            dataBinding.loading.isVisible = state.loading && !state.hasDirectory
            dataBinding.refreshLayout.isVisible = !state.loading || state.hasDirectory
            dataBinding.refreshLayout.isRefreshing = state.loading && state.hasDirectory
            if (state.hasDirectory && !state.loading) {
                ownerActivity.onDirectoryOpened(this, state.directory, state.directoryFiles, it.filterIsInstance<StorageFile>())
            }
            dataBinding.storageFileRv.setData(it)
            // The adapter reuses one empty-item sentinel; its diff cannot observe state-only changes.
            if (it.isEmpty()) dataBinding.storageFileRv.adapter?.notifyItemChanged(0)
            if (state.error != null && state.error != lastError && state.items.isNotEmpty() && ownerActivity.isCurrentDirectory(this)) {
                ToastCenter.showError(state.error)
            }
            lastError = state.error
            val list = dataBinding.storageFileRv as? DpadRecyclerView
            if (list != null && restoreLayout && !state.loading) {
                restoreLayout = false
                listState.getScrollState().restore(list, listKey, requireNotNull(list.adapter))
                val position = it.indexOfFirst { file -> (file as? StorageFile)?.uniqueKey() == savedFileKey }
                if (savedFileKey != null && position >= 0) list.setSelectedPosition(position)
            }

            if (!dataBinding.storageFileRv.isInTouchMode) {
                val pagingItem = it.lastOrNull() as? StoragePagingItem
                if (pagingItem != null) {
                    if (pagingItem.state != lastPagingHintState) {
                        lastPagingHintState = pagingItem.state
                        if (pagingItem.state == com.xyoye.common_component.storage.PagedStorage.State.ERROR) {
                            ToastCenter.showError("加载失败，按确认键重试")
                        }
                    }
                }
            }
        }

        viewModel.loginRequiredLiveData.observe(viewLifecycleOwner) { library ->
            if (library != null && ownerActivity.isCurrentDirectory(this)) {
                viewModel.consumeLoginRequest()
                showLoginDialog(library)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!loaded || viewModel.state.value?.hasDirectory != true) {
            loaded = true
            reloadDirectory()
        } else {
            viewModel.updateHistory()
        }
    }

    override fun onPause() {
        viewModel.cancelRequests()
        super.onPause()
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

            (this as? DpadRecyclerView)?.apply {
                isSaveEnabled = false
                setFocusOutAllowed(true, false)
            }
        }
    }

    override fun requestDefaultDpadFocus(): Boolean {
        val binding = bindingOrNull ?: return false
        if (binding.root.isInTouchMode || !ownerActivity.isCurrentDirectory(this)) return false
        return binding.storageFileRv.requestFocus()
    }

    fun triggerTvRefresh() {
        val binding = bindingOrNull ?: return
        val recyclerView = binding.storageFileRv
        if (recyclerView.isInTouchMode) {
            reloadDirectory(refresh = true)
            return
        }

        ToastCenter.showInfo("刷新中…")
        reloadDirectory(refresh = true)
    }

    fun reloadDirectory(refresh: Boolean = false) {
        if (refresh) {
            dataBinding.refreshLayout.isRefreshing = true
        }
        viewModel.listFile(directory, refresh, arguments?.getString("directory_path"))
    }

    fun requestFocus() {
        requestDefaultDpadFocus()
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
        if (!ownerActivity.isCurrentDirectory(this)) return
        (binding.storageFileRv as? DpadRecyclerView)?.setSelectedPosition(targetIndex)
        requestFocus()
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
            MediaType.BAIDU_PAN_STORAGE -> showBaiduPanLoginDialog(library)
            MediaType.OPEN_115_STORAGE -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Stream.StoragePlus)
                    .withSerializable("mediaType", library.mediaType)
                    .withParcelable("editData", library)
                    .navigation(ownerActivity, StorageFileActivity.REQUEST_CODE_OPEN115_REAUTH)
            }
            MediaType.CLOUD_115_STORAGE -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Stream.StoragePlus)
                    .withSerializable("mediaType", library.mediaType)
                    .withParcelable("editData", library)
                    .navigation(ownerActivity, StorageFileActivity.REQUEST_CODE_CLOUD115_REAUTH)
            }
            else -> Unit
        }
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
                    updatedAtMs = nowMs,
                )
                BaiduPanAuthStore.writeProfile(
                    storageKey = storageKey,
                    uk = uk,
                    netdiskName = uinfo.netdiskName,
                    avatarUrl = uinfo.avatarUrl,
                    updatedAtMs = nowMs,
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
                        (binding.storageFileRv as? DpadRecyclerView)?.setSelectedPosition(0)
                    }
                }
            },
        ).show()
    }
}
