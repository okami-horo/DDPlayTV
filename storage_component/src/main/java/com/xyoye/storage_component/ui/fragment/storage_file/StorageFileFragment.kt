package com.xyoye.storage_component.ui.fragment.storage_file

import android.view.KeyEvent
import android.view.View
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.toResString
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.FragmentStorageFileBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity

class StorageFileFragment :
    BaseFragment<StorageFileFragmentViewModel, FragmentStorageFileBinding>() {

    private val directory: StorageFile? by lazy { ownerActivity.directory }

    companion object {

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
            dataBinding.refreshLayout.isVisible = true
            dataBinding.refreshLayout.isRefreshing = false
            ownerActivity.onDirectoryOpened(it)
            dataBinding.storageFileRv.setData(it)
            //延迟500毫秒，等待列表加载完成后，再请求焦点
            dataBinding.storageFileRv.postDelayed({ requestFocus() }, 500)
        }

        dataBinding.refreshLayout.setColorSchemeResources(R.color.theme)
        dataBinding.refreshLayout.setOnRefreshListener {
            viewModel.listFile(directory, refresh = true)
        }

        viewModel.listFile(directory)
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateHistory()
        setRecyclerViewItemFocusAble(true)
    }

    override fun onPause() {
        super.onPause()
        setRecyclerViewItemFocusAble(false)
    }

    private fun setRecyclerViewItemFocusAble(focusAble: Boolean) {
        val focusTag = R.string.focusable_item.toResString()
        val binding = bindingOrNull ?: return
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
                    return@setOnKeyListener false
                }

                val adapter = adapter ?: return@setOnKeyListener false
                val focusedChild = focusedChild ?: return@setOnKeyListener false
                val currentIndex = getChildAdapterPosition(focusedChild)
                if (currentIndex == RecyclerView.NO_POSITION) {
                    return@setOnKeyListener false
                }

                return@setOnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val nextIndex = currentIndex + 1
                        if (nextIndex < adapter.itemCount) {
                            // 遥控器按下时手动分发焦点，保证列表可继续向下滚动
                            requestIndexChildFocus(nextIndex)
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val previousIndex = currentIndex - 1
                        if (previousIndex >= 0) {
                            requestIndexChildFocus(previousIndex)
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        }
    }

    fun requestFocus(reversed: Boolean = false) {
        val binding = bindingOrNull ?: return
        val adapter = binding.storageFileRv.adapter ?: return
        if (adapter.itemCount == 0) {
            return
        }
        val targetIndex = if (reversed) adapter.itemCount - 1 else 0
        if (binding.storageFileRv.requestIndexChildFocus(targetIndex)) {
            return
        }
        binding.storageFileRv.post {
            bindingOrNull?.storageFileRv?.requestIndexChildFocus(targetIndex)
        }
    }

    /**
     * 搜索
     */
    fun search(text: String) {
        //存在搜索条件时，不允许下拉刷新
        dataBinding.refreshLayout.isEnabled = text.isEmpty()
        viewModel.searchByText(text)
    }

    /**
     * 修改文件排序
     */
    fun sort() {
        viewModel.changeSortOption()
    }
}