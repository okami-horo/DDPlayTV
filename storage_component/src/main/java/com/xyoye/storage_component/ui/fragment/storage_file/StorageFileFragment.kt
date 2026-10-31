package com.xyoye.storage_component.ui.fragment.storage_file

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
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
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.formatDuration
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
    private var savedMore = false
    private var applyingState = false
    private var previewImage: Pair<String, String?>? = null
    private lateinit var fileAdapter: StorageFileAdapter
    val query: String get() =
        viewModel.state.value
            ?.query
            .orEmpty()

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
        savedMore = savedInstanceState?.getBoolean("focused_more") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberFile()
        (bindingOrNull?.storageFileRv as? DpadRecyclerView)?.let {
            listState.getScrollState().save(it, listKey, detachAdapter = false)
        }
        outState.putString("focused_file", savedFileKey)
        outState.putBoolean("focused_more", savedMore)
        super.onSaveInstanceState(outState)
    }

    private fun rememberFile() {
        val list = bindingOrNull?.storageFileRv as? DpadRecyclerView ?: return
        ((list.adapter as? BaseAdapter)?.items?.getOrNull(list.getSelectedPosition()) as? StorageFile)?.let {
            savedFileKey = it.browserKey()
            if (list.hasFocus()) savedMore = activity?.currentFocus?.id == R.id.more_action_iv
        }
    }

    override fun onDestroyView() {
        rememberFile()
        (bindingOrNull?.storageFileRv as? DpadRecyclerView)?.let {
            listState.getScrollState().save(it, listKey)
        }
        bindingOrNull?.previewCover?.dispose()
        previewImage = null
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

        dataBinding.emptyBack.setOnClickListener { ownerActivity.backDirectory() }
        viewModel.state.observe(viewLifecycleOwner, ::renderState)
    }

    private fun renderState(state: StorageBrowseState) {
        val binding = bindingOrNull ?: return
        val list = binding.storageFileRv as DpadRecyclerView
        val files = state.items.filterIsInstance<StorageFile>()
        val hasRows = files.isNotEmpty()
        val pageLoading = (state.items.lastOrNull() as? StoragePagingItem)?.state == PagedStorage.State.LOADING
        val initial = restoreLayout && state.hasDirectory && !state.loading
        val oldItems = (list.adapter as BaseAdapter).items
        val wasPaging = oldItems.getOrNull(list.getSelectedPosition()) is StoragePagingItem
        val terminalPage =
            (state.items.lastOrNull() as? StoragePagingItem)?.let {
                !it.hasMore && it.state != PagedStorage.State.LOADING && it.state != PagedStorage.State.ERROR
            } == true
        val previousKey = savedFileKey
        val previousMore = savedMore
        val oldFocusInContent =
            list.hasFocus() ||
                binding.browserEmpty.hasFocus() ||
                activity?.currentFocus?.id == R.id.browser_status
        applyingState = true
        binding.refreshLayout.isVisible = hasRows
        binding.refreshLayout.isRefreshing = state.loading && hasRows && !pageLoading
        binding.browserEmpty.isVisible = !hasRows
        binding.previewPanel.isVisible = hasRows
        list.setData(state.items)
        val selected = files.firstOrNull { it.browserKey() == previousKey } ?: files.firstOrNull()
        savedFileKey = selected?.browserKey() ?: if (state.loading || !state.hasDirectory) previousKey else null
        savedMore = previousMore && selected?.browserKey() == previousKey
        if (initial) {
            restoreLayout = false
            listState.getScrollState().restore(list, listKey, requireNotNull(list.adapter))
        }
        if (selected != null && (!wasPaging || terminalPage || initial)) {
            val index = state.items.indexOf(selected)
            if (initial || list.getSelectedPosition() != index) list.setSelectedPosition(index)
        }
        renderPreview(selected)
        if (state.hasDirectory && !state.loading) {
            ownerActivity.onDirectoryOpened(this, state.directory, state.directoryFiles, files)
        } else if (!state.hasDirectory) {
            ownerActivity.showDirectoryLoading(this)
        }
        if (!hasRows && (state.loading || state.error != null)) ownerActivity.showDirectoryLoading(this)
        val pageError = (state.items.lastOrNull() as? StoragePagingItem)?.state == PagedStorage.State.ERROR
        val retainedError = state.error != null && hasRows && !pageError
        val status =
            when {
                retainedError && state.loginRequired -> "授权失效 · 已保留当前内容 · 重新授权"
                retainedError -> "刷新失败 · 已保留当前内容 · 重试"
                !hasRows && state.loginRequired -> "等待重新授权"
                !hasRows && state.error != null && !state.loading -> "目录读取失败"
                pageLoading -> "已加载 " + files.size + " 个项目"
                state.loading -> "正在读取目录…"
                state.query.isNotEmpty() -> "搜索：" + state.query
                (state.items.lastOrNull() as? StoragePagingItem)?.hasMore == true -> "已加载 " + files.size + " 个项目"
                state.hasDirectory -> "已全部显示"
                else -> ""
            }
        ownerActivity.showBrowserStatus(
            this,
            status,
            if (retainedError) {
                (
                    {
                        if (state.loginRequired) showLoginDialog(ownerActivity.storage.library) else triggerTvRefresh()
                    }
                )
            } else {
                null
            }
        )
        if (!hasRows) renderEmpty(state)
        applyingState = false
        if (initial || oldFocusInContent || (restoreLayout && !state.loading && !hasRows)) {
            list.doOnNextLayout {
                if (bindingOrNull === binding && ownerActivity.isCurrentDirectory(this)) requestDefaultDpadFocus()
            }
            list.requestLayout()
            if (!hasRows) requestDefaultDpadFocus()
        }
    }

    private fun renderEmpty(state: StorageBrowseState) {
        val binding = dataBinding
        val paging = state.items.lastOrNull() as? StoragePagingItem
        val canLoadPage = paging?.hasMore == true && state.query.isEmpty()
        binding.loading.isVisible = state.loading
        binding.emptyIcon.isVisible = !state.loading
        binding.emptyIcon.setImageResource(if (state.error != null) R.drawable.file_browser_warning else R.drawable.file_browser_folder)
        binding.emptyTv.text =
            when {
                state.loading -> "正在读取目录"
                state.loginRequired -> "登录已失效"
                state.error != null -> "暂时无法读取目录"
                state.query.isNotEmpty() -> "没有找到匹配的文件"
                canLoadPage -> "此页暂无可播放文件"
                else -> "这个文件夹是空的"
            }
        binding.emptyDescription.text =
            when {
                state.loading -> "文件较多时，可能需要稍等片刻。"
                state.loginRequired -> "重新授权后可以继续浏览当前来源。"
                state.error != null -> "请检查网络连接，然后重试。"
                state.query.isNotEmpty() -> "试试其他关键词，或清除搜索。"
                canLoadPage -> "可以继续加载下一页。"
                else -> "可以返回上一级，继续浏览其他内容。"
            }
        binding.emptyActionTv.isVisible = !state.loading
        binding.emptyActionTv.text =
            when {
                state.loginRequired -> "重新授权"
                state.error != null -> "重试加载"
                canLoadPage -> "加载更多"
                state.query.isNotEmpty() -> "清除搜索"
                else -> "刷新"
            }
        binding.emptyActionTv.setOnClickListener {
            when {
                state.loginRequired -> showLoginDialog(ownerActivity.storage.library)
                canLoadPage -> viewModel.loadMore()
                state.query.isNotEmpty() && state.error == null -> search("")
                else -> triggerTvRefresh()
            }
        }
    }

    private fun renderPreview(file: StorageFile?) {
        val binding = bindingOrNull ?: return
        if (file == null) {
            binding.previewCover.dispose()
            binding.previewCover.setImageDrawable(null)
            binding.previewTitle.text = ""
            binding.previewDetails.text = ""
            binding.previewPath.text = ""
            previewImage = null
            return
        }
        binding.previewTitle.text = file.fileName()
        val duration = (file.playHistory?.videoDuration ?: 0).takeIf { it > 0 } ?: file.videoDuration()
        binding.previewDetails.text = fileBrowserDetails(requireContext(), file) +
            if (file.isFile() && duration > 0) " · " + formatDuration(duration) else ""
        binding.previewPath.text = ownerActivity.storage.library.displayName + " / " +
            (
                viewModel.state.value
                    ?.directory
                    ?.storagePath()
                    ?.trim('/')
                    ?.takeUnless { it.isBlank() } ?: "根目录"
            )
        binding.previewHint.text = if (file.isDirectory()) "按确定打开文件夹" else "按确定播放 · 按菜单查看文件操作"
        val image = file.browserKey() to file.fileCover()
        if (image != previewImage) {
            previewImage = image
            binding.previewCover.dispose()
            binding.previewCover.setImageDrawable(null)
            binding.previewIcon.isVisible = file.isDirectory()
            if (file.isFile()) {
                // Coil's ImageView target cancels the old request on replacement/detach.
                binding.previewCover.load(image.second) {
                    crossfade(false)
                    placeholder(R.drawable.file_browser_preview_placeholder)
                    fallback(R.drawable.file_browser_preview_placeholder)
                    error(R.drawable.file_browser_preview_placeholder)
                }
            }
        }
    }

    private fun fileFocused(
        file: StorageFile,
        more: Boolean
    ) {
        if (applyingState || !ownerActivity.isCurrentDirectory(this)) return
        savedFileKey = file.browserKey()
        savedMore = more
        renderPreview(file)
    }

    fun showFocusedActions(): Boolean {
        val list = bindingOrNull?.storageFileRv as? DpadRecyclerView ?: return false
        if (!list.hasFocus()) return false
        val file = (list.adapter as? BaseAdapter)?.items?.getOrNull(list.getSelectedPosition()) as? StorageFile ?: return false
        val row = list.findViewHolderForAdapterPosition(list.getSelectedPosition())?.itemView as? ConstraintLayout ?: return false
        fileAdapter.showMoreAction(file, row)
        return true
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
        rememberFile()
        viewModel.cancelRequests()
        super.onPause()
    }

    private fun initRecyclerView() {
        dataBinding.storageFileRv.apply {
            layoutManager = vertical()
            addItemDecoration(
                com.xyoye.common_component.utils.view
                    .ItemDecorationOrientation((6 * resources.displayMetrics.density).toInt(), 0, RecyclerView.VERTICAL)
            )

            fileAdapter = StorageFileAdapter(ownerActivity, viewModel, ::fileFocused)
            adapter = fileAdapter.create()

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
        if (binding.browserEmpty.isVisible) {
            return if (binding.emptyActionTv.isVisible) binding.emptyActionTv.requestFocus() else binding.emptyBack.requestFocus()
        }
        val list = binding.storageFileRv as DpadRecyclerView
        val row = list.findViewHolderForAdapterPosition(list.getSelectedPosition())?.itemView
        if (savedMore && row?.findViewById<View>(R.id.more_action_iv)?.isFocusable == true) {
            return row.findViewById<View>(R.id.more_action_iv).requestFocus()
        }
        return list.requestFocus()
    }

    fun triggerTvRefresh() {
        val binding = bindingOrNull ?: return
        val recyclerView = binding.storageFileRv
        if (recyclerView.isInTouchMode) {
            reloadDirectory(refresh = true)
            return
        }

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
        viewModel.consumeLoginRequest()
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
