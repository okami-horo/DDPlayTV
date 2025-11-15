package com.xyoye.storage_component.ui.fragment.storage_file

import android.view.KeyEvent
import android.view.View
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.toResString
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.DDLog
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.FragmentStorageFileBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity

class StorageFileFragment :
    BaseFragment<StorageFileFragmentViewModel, FragmentStorageFileBinding>() {

    private val directory: StorageFile? by lazy { ownerActivity.directory }
    private var lastFocusedIndex = RecyclerView.NO_POSITION
    private var pendingFocusIndex = RecyclerView.NO_POSITION

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
            StorageFileFragmentViewModel::class.java
        )

    override fun getLayoutId() = R.layout.fragment_storage_file

    override fun initView() {
        initRecyclerView()

        viewModel.storage = ownerActivity.storage

        viewModel.fileLiveData.observe(this) {
            dataBinding.loading.isVisible = false
            dataBinding.storageFileRv.isVisible = true
            ownerActivity.onDirectoryOpened(it)
            dataBinding.storageFileRv.setData(it)
            ownerActivity.onDirectoryDataLoaded(this, it)
            if (ownerActivity.isLocatingLastPlay().not()) {
                //延迟500毫秒，等待列表加载完成后，再请求焦点
                dataBinding.storageFileRv.postDelayed({ requestFocus() }, 500)
            }
        }

        viewModel.listFile(directory)
    }

    override fun onResume() {
        super.onResume()
        if (lastFocusedIndex != RecyclerView.NO_POSITION) {
            pendingFocusIndex = lastFocusedIndex
        }
        viewModel.updateHistory()
        setRecyclerViewItemFocusAble(true)
    }

    override fun onPause() {
        saveCurrentFocusIndex()
        super.onPause()
        setRecyclerViewItemFocusAble(false)
    }

    private fun setRecyclerViewItemFocusAble(focusAble: Boolean) {
        val focusTag = R.string.focusable_item.toResString()
        val binding = bindingOrNull ?: return
        DDLog.i(TAG, "setRecyclerViewItemFocusAble focusAble=$focusAble childCount=${binding.storageFileRv.childCount}")
        binding.storageFileRv.children.forEach { child ->
            val target = child.findViewWithTag<View>(focusTag) ?: child
            target.isFocusable = focusAble
            target.isFocusableInTouchMode = focusAble
        }
    }

    private fun initRecyclerView() {
        dataBinding.storageFileRv.apply {
            layoutManager = vertical()

            adapter = StorageFileAdapter(ownerActivity, viewModel).create()

            setOnKeyListener { _, keyCode, event ->
                if (event?.action != KeyEvent.ACTION_DOWN) {
                    DDLog.i(TAG, "ignore key action=${event?.action} keyCode=$keyCode")
                    return@setOnKeyListener false
                }

                val rvAdapter = adapter ?: run {
                    DDLog.w(TAG, "key event adapter null keyCode=$keyCode")
                    return@setOnKeyListener false
                }
                val focusedChild = focusedChild ?: run {
                    DDLog.w(TAG, "key event focusedChild null keyCode=$keyCode")
                    return@setOnKeyListener false
                }
                val currentIndex = getChildAdapterPosition(focusedChild)
                if (currentIndex == RecyclerView.NO_POSITION) {
                    DDLog.w(TAG, "key event invalid position keyCode=$keyCode")
                    return@setOnKeyListener false
                }

                return@setOnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val nextIndex = currentIndex + 1
                        if (nextIndex < rvAdapter.itemCount) {
                            // 遥控器按下时手动分发焦点，保证列表可继续向下滚动
                            val moved = requestIndexChildFocus(nextIndex)
                            DDLog.i(
                                TAG,
                                "key DOWN current=$currentIndex target=$nextIndex moved=$moved count=${rvAdapter.itemCount} repeat=${event.repeatCount}"
                            )
                            if (moved) {
                                lastFocusedIndex = nextIndex
                                pendingFocusIndex = RecyclerView.NO_POSITION
                            }
                            moved
                        } else {
                            DDLog.w(
                                TAG,
                                "key DOWN reach end current=$currentIndex count=${rvAdapter.itemCount}"
                            )
                            true
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val previousIndex = currentIndex - 1
                        if (previousIndex >= 0) {
                            val moved = requestIndexChildFocus(previousIndex)
                            DDLog.i(
                                TAG,
                                "key UP current=$currentIndex target=$previousIndex moved=$moved count=${rvAdapter.itemCount} repeat=${event.repeatCount}"
                            )
                            if (moved) {
                                lastFocusedIndex = previousIndex
                                pendingFocusIndex = RecyclerView.NO_POSITION
                            }
                            moved
                        } else {
                            DDLog.w(
                                TAG,
                                "key UP reach top current=$currentIndex"
                            )
                            false
                        }
                    }

                    else -> {
                        DDLog.i(TAG, "key pass-through keyCode=$keyCode index=$currentIndex")
                        false
                    }
                }
            }
        }
    }

    fun reloadDirectory(refresh: Boolean = false) {
        viewModel.listFile(directory, refresh)
    }

    fun requestFocus(reversed: Boolean = false) {
        val binding = bindingOrNull ?: return
        val adapter = binding.storageFileRv.adapter ?: return
        if (adapter.itemCount == 0) {
            DDLog.w(TAG, "requestFocus skip empty adapter")
            return
        }
        val hasPending = pendingFocusIndex != RecyclerView.NO_POSITION && !reversed
        val desiredIndex = when {
            hasPending -> pendingFocusIndex
            reversed -> adapter.itemCount - 1
            else -> 0
        }
        val targetIndex = desiredIndex.coerceIn(0, adapter.itemCount - 1)
        pendingFocusIndex = RecyclerView.NO_POSITION
        DDLog.i(TAG, "requestFocus start reversed=$reversed count=${adapter.itemCount} target=$targetIndex")
        if (binding.storageFileRv.requestIndexChildFocus(targetIndex)) {
            DDLog.i(TAG, "requestFocus direct success target=$targetIndex")
            lastFocusedIndex = targetIndex
            return
        }
        binding.storageFileRv.post {
            bindingOrNull?.storageFileRv?.let {
                val postResult = it.requestIndexChildFocus(targetIndex)
                if (postResult) {
                    lastFocusedIndex = targetIndex
                }
                DDLog.i(TAG, "requestFocus post result=$postResult target=$targetIndex")
            }
        }
    }

    fun focusFile(uniqueKey: String) {
        val binding = bindingOrNull ?: return
        val adapter = binding.storageFileRv.adapter as? BaseAdapter ?: return
        val targetIndex = adapter.items.indexOfFirst { item ->
            val storageFile = item as? StorageFile ?: return@indexOfFirst false
            storageFile.playHistory?.uniqueKey == uniqueKey
        }
        if (targetIndex == -1) {
            DDLog.w(TAG, "focusFile target not found uniqueKey=$uniqueKey")
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
            DDLog.i(TAG, "saveCurrentFocusIndex index=$index")
        }
    }

}
