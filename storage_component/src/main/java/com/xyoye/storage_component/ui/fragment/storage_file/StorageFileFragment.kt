package com.xyoye.storage_component.ui.fragment.storage_file

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.extension.FocusTarget
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.toResString
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.impl.BilibiliStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.FragmentStorageFileBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.dialog.BilibiliLoginDialog

class StorageFileFragment : BaseFragment<StorageFileFragmentViewModel, FragmentStorageFileBinding>() {
    private val directory: StorageFile? by lazy { ownerActivity.directory }
    private var lastFocusedIndex = RecyclerView.NO_POSITION
    private var pendingFocusIndex = RecyclerView.NO_POSITION
    private var pendingFocusUniqueKey: String? = null
    private var rootDescendantFocusability: Int? = null
    private var tvHistoryHintShown = false
    private var lastPagingHintState: com.xyoye.common_component.storage.PagedStorage.State? = null

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
            ownerActivity.onDirectoryOpened(it.filterIsInstance<StorageFile>())
            dataBinding.storageFileRv.setData(it)

            if (!dataBinding.storageFileRv.isInTouchMode) {
                val pagingItem = it.lastOrNull() as? StoragePagingItem
                if (pagingItem != null) {
                    if (!tvHistoryHintShown &&
                        ownerActivity.storage.library.mediaType == MediaType.BILIBILI_STORAGE &&
                        ownerActivity.directory?.filePath() == "/history/"
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
                pendingFocusIndex == RecyclerView.NO_POSITION &&
                pendingFocusUniqueKey == null
            ) {
                val showEmptyActionFocus =
                    it.isEmpty() ||
                        ((it.singleOrNull() as? StoragePagingItem)?.isDataEmpty == true)
                if (showEmptyActionFocus) {
                    pendingFocusIndex = 0
                }
            }

            if (pendingFocusIndex != RecyclerView.NO_POSITION || pendingFocusUniqueKey != null) {
                // 触控模式下不恢复列表焦点，避免出现“默认高亮/焦点框”
                if (dataBinding.storageFileRv.isInTouchMode) {
                    pendingFocusIndex = RecyclerView.NO_POSITION
                    pendingFocusUniqueKey = null
                    return@observe
                }

                val adapterCount = dataBinding.storageFileRv.adapter?.itemCount ?: 0
                if (adapterCount <= 0) {
                    LogFacade.d(LogModule.STORAGE, TAG, "pending focus skipped, adapter empty")
                    return@observe
                }

                pendingFocusIndex = resolvePendingFocusIndex(it, adapterCount)
                pendingFocusUniqueKey = null
                // 等待下一次消息循环后再请求焦点，避免因子 View 未就绪导致的滚动跳变
                dataBinding.storageFileRv.post { requestFocus() }
            }
        }

        viewModel.bilibiliLoginRequiredLiveData.observe(this) { library ->
            if (library.mediaType != com.xyoye.data_component.enums.MediaType.BILIBILI_STORAGE) {
                return@observe
            }
            showBilibiliLoginDialog(library)
        }

        viewModel.listFile(directory)
    }

    override fun onResume() {
        super.onResume()
        setFragmentFocusBlocked(false)
        if (lastFocusedIndex != RecyclerView.NO_POSITION) {
            pendingFocusIndex = lastFocusedIndex
        }
        viewModel.updateHistory()
        setRecyclerViewItemFocusAble(true)
    }

    override fun onPause() {
        saveCurrentFocusIndex()
        super.onPause()
        setFragmentFocusBlocked(true)
        setRecyclerViewItemFocusAble(false)
    }

    /**
     * StorageFileActivity stacks fragments via add(), so non-current fragments keep their view in the hierarchy.
     * If we don't block focus for non-current fragments, TV DPAD navigation may "pierce" into invisible pages.
     */
    private fun setFragmentFocusBlocked(blocked: Boolean) {
        val binding = bindingOrNull ?: return
        val root = binding.root as? ViewGroup ?: return
        if (rootDescendantFocusability == null) {
            rootDescendantFocusability = root.descendantFocusability
        }

        if (blocked) {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.clearFocus()
        } else {
            root.descendantFocusability = rootDescendantFocusability ?: ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun setRecyclerViewItemFocusAble(focusAble: Boolean) {
        val focusTag = R.string.focusable_item.toResString()
        val binding = bindingOrNull ?: return
        val inTouchMode = binding.storageFileRv.isInTouchMode
        LogFacade.d(
            LogModule.STORAGE,
            TAG,
            "setRecyclerViewItemFocusAble focusAble=$focusAble childCount=${binding.storageFileRv.childCount}",
        )
        binding.storageFileRv.children.forEach { child ->
            val target = child.findViewWithTag<View>(focusTag) ?: child
            target.isFocusable = focusAble
            target.isFocusableInTouchMode = focusAble && !inTouchMode
        }
    }

    private fun initRecyclerView() {
        dataBinding.storageFileRv.apply {
            layoutManager = vertical()

            adapter =
                StorageFileAdapter(
                    activity = ownerActivity,
                    viewModel = viewModel,
                    onRefreshRequested = { triggerTvRefresh() },
                    onLoginRequested = { showBilibiliLoginDialog(ownerActivity.storage.library) },
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

            setOnKeyListener { _, keyCode, event ->
                if (event?.action != KeyEvent.ACTION_DOWN) {
                    LogFacade.d(LogModule.STORAGE, TAG, "ignore key action=${event?.action} keyCode=$keyCode")
                    return@setOnKeyListener false
                }

                val rvAdapter =
                    adapter ?: run {
                        LogFacade.w(LogModule.STORAGE, TAG, "key event adapter null keyCode=$keyCode")
                        return@setOnKeyListener false
                    }
                val focusedChild =
                    focusedChild ?: run {
                        LogFacade.w(LogModule.STORAGE, TAG, "key event focusedChild null keyCode=$keyCode")
                        return@setOnKeyListener false
                    }
                val currentIndex = getChildAdapterPosition(focusedChild)
                if (currentIndex == RecyclerView.NO_POSITION) {
                    LogFacade.w(LogModule.STORAGE, TAG, "key event invalid position keyCode=$keyCode")
                    return@setOnKeyListener false
                }

                return@setOnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_SETTINGS -> {
                        // TV/遥控器：提供可达的刷新入口（替代下拉刷新）
                        triggerTvRefresh()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val nextIndex = currentIndex + 1
                        if (nextIndex < rvAdapter.itemCount) {
                            // 遥控器按下时手动分发焦点，保证列表可继续向下滚动
                            val moved = requestIndexChildFocus(nextIndex, resolveVerticalMoveFocusTarget())
                            LogFacade.d(
                                LogModule.STORAGE,
                                TAG,
                                "key DOWN current=$currentIndex target=$nextIndex moved=$moved count=${rvAdapter.itemCount} repeat=${event.repeatCount}",
                            )
                            if (moved) {
                                lastFocusedIndex = nextIndex
                                pendingFocusIndex = RecyclerView.NO_POSITION
                            }
                            moved
                        } else {
                            LogFacade.d(
                                LogModule.STORAGE,
                                TAG,
                                "key DOWN reach end current=$currentIndex count=${rvAdapter.itemCount}",
                            )
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val previousIndex = currentIndex - 1
                        if (previousIndex >= 0) {
                            val moved = requestIndexChildFocus(previousIndex, resolveVerticalMoveFocusTarget())
                            LogFacade.d(
                                LogModule.STORAGE,
                                TAG,
                                "key UP current=$currentIndex target=$previousIndex moved=$moved count=${rvAdapter.itemCount} repeat=${event.repeatCount}",
                            )
                            if (moved) {
                                lastFocusedIndex = previousIndex
                                pendingFocusIndex = RecyclerView.NO_POSITION
                            }
                            moved
                        } else {
                            LogFacade.d(LogModule.STORAGE, TAG, "key UP reach top current=$currentIndex")
                            false
                        }
                    }

                    else -> {
                        LogFacade.d(LogModule.STORAGE, TAG, "key pass-through keyCode=$keyCode index=$currentIndex")
                        false
                    }
                }
            }
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
                directory?.filePath() == "/history/"

        if (isBilibiliHistory) {
            // 历史列表刷新：刷新后聚焦第一项（最新）
            pendingFocusUniqueKey = null
            pendingFocusIndex = 0
        } else {
            val (focusedIndex, focusedUniqueKey) = captureFocusedItem()
            pendingFocusUniqueKey = focusedUniqueKey
            pendingFocusIndex =
                when {
                    focusedIndex != RecyclerView.NO_POSITION -> focusedIndex
                    else -> 0
                }
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
        val recyclerView = binding.storageFileRv
        if (recyclerView.isInTouchMode) {
            LogFacade.d(LogModule.STORAGE, TAG, "requestFocus skipped (touch mode)")
            return
        }
        val adapter = recyclerView.adapter ?: return
        if (adapter.itemCount == 0) {
            LogFacade.d(LogModule.STORAGE, TAG, "requestFocus skip empty adapter")
            return
        }
        val hasPending = pendingFocusIndex != RecyclerView.NO_POSITION && !reversed
        val desiredIndex =
            when {
                hasPending -> pendingFocusIndex
                reversed -> adapter.itemCount - 1
                else -> 0
            }
        val targetIndex = desiredIndex.coerceIn(0, adapter.itemCount - 1)
        pendingFocusIndex = RecyclerView.NO_POSITION
        LogFacade.d(
            LogModule.STORAGE,
            TAG,
            "requestFocus start reversed=$reversed count=${adapter.itemCount} target=$targetIndex",
        )
        requestIndexFocusWhenReady(targetIndex)
    }

    private fun requestIndexFocusWhenReady(
        targetIndex: Int,
        attempt: Int = 0
    ) {
        val binding = bindingOrNull ?: return
        val recyclerView = binding.storageFileRv
        if (recyclerView.isInTouchMode) {
            LogFacade.d(LogModule.STORAGE, TAG, "requestIndexFocusWhenReady skipped (touch mode)")
            return
        }

        if (tryRequestIndexFocus(targetIndex)) {
            lastFocusedIndex = targetIndex
            LogFacade.d(LogModule.STORAGE, TAG, "requestIndexFocusWhenReady success attempt=$attempt target=$targetIndex")
            return
        }

        if (attempt < 3) {
            recyclerView.post { requestIndexFocusWhenReady(targetIndex, attempt + 1) }
            return
        }

        // 最后兜底：滚动到目标位置，再请求焦点（用于目标项不在可见区域的场景）
        val moved = recyclerView.requestIndexChildFocus(targetIndex)
        LogFacade.d(LogModule.STORAGE, TAG, "requestIndexFocusWhenReady fallback moved=$moved target=$targetIndex")
        if (moved) {
            lastFocusedIndex = targetIndex
        }
    }

    private fun tryRequestIndexFocus(targetIndex: Int): Boolean {
        val binding = bindingOrNull ?: return false
        val recyclerView = binding.storageFileRv
        val layoutManager = recyclerView.layoutManager ?: return false
        val target = layoutManager.findViewByPosition(targetIndex) ?: return false

        // 保证目标项完整可见，避免只露出一部分导致的视效跳动
        val rect = Rect()
        target.getDrawingRect(rect)
        recyclerView.offsetDescendantRectToMyCoords(target, rect)
        recyclerView.requestChildRectangleOnScreen(target, rect, true)

        val targetTag = R.string.focusable_item.toResString()
        val focusView = target.findViewWithTag<View>(targetTag) ?: target.takeIf { it.isFocusable }
        return focusView?.requestFocus() == true
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
        binding.storageFileRv.post {
            bindingOrNull?.storageFileRv?.requestIndexChildFocus(targetIndex)
        }
        lastFocusedIndex = targetIndex
        pendingFocusIndex = RecyclerView.NO_POSITION
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

    private fun saveCurrentFocusIndex() {
        val binding = bindingOrNull ?: return
        val focusedChild = binding.storageFileRv.focusedChild ?: return
        val index = binding.storageFileRv.getChildAdapterPosition(focusedChild)
        if (index != RecyclerView.NO_POSITION) {
            lastFocusedIndex = index
            pendingFocusIndex = index
            LogFacade.d(LogModule.STORAGE, TAG, "saveCurrentFocusIndex index=$index")
        }
    }

    private fun resolvePendingFocusIndex(
        items: List<Any>,
        itemCount: Int,
    ): Int {
        val pendingKey = pendingFocusUniqueKey
        if (!pendingKey.isNullOrEmpty()) {
            val resolvedIndex =
                items.indexOfFirst { item ->
                    val file = item as? StorageFile ?: return@indexOfFirst false
                    file.uniqueKey() == pendingKey
                }
            if (resolvedIndex in 0 until itemCount) {
                return resolvedIndex
            }
        }

        if (pendingFocusIndex != RecyclerView.NO_POSITION) {
            return pendingFocusIndex.coerceIn(0, itemCount - 1)
        }

        return 0
    }

    private fun captureFocusedItem(): Pair<Int, String?> {
        val binding = bindingOrNull ?: return RecyclerView.NO_POSITION to null
        val recyclerView = binding.storageFileRv
        val focusedChild =
            recyclerView.focusedChild
                ?: return RecyclerView.NO_POSITION to null
        val focusedIndex = recyclerView.getChildAdapterPosition(focusedChild)
        if (focusedIndex == RecyclerView.NO_POSITION) {
            return focusedIndex to null
        }

        val adapter = recyclerView.adapter as? BaseAdapter ?: return focusedIndex to null
        val storageFile = adapter.items.getOrNull(focusedIndex) as? StorageFile ?: return focusedIndex to null
        return focusedIndex to storageFile.uniqueKey()
    }

    private fun showBilibiliLoginDialog(library: MediaLibraryEntity) {
        BilibiliLoginDialog(
            activity = ownerActivity,
            library = library,
            onLoginSuccess = { triggerTvRefresh() },
            onDismiss = {
                val isHistoryPage = directory?.filePath() == "/history/"
                val requiresLogin = (ownerActivity.storage as? BilibiliStorage)?.isConnected() == false
                bindingOrNull?.let { binding ->
                    if (!binding.storageFileRv.isInTouchMode && isHistoryPage && requiresLogin) {
                        pendingFocusIndex = 0
                        binding.storageFileRv.post { requestFocus() }
                    }
                }
            },
        ).show()
    }
}
