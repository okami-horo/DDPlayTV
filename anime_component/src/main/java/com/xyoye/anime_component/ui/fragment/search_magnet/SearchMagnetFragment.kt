package com.xyoye.anime_component.ui.fragment.search_magnet

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.FragmentSearchMagnetBinding
import com.xyoye.anime_component.databinding.ItemSearchMagnetBinding
import com.xyoye.anime_component.listener.SearchListener
import com.xyoye.anime_component.ui.activities.search.SearchActivity
import com.xyoye.anime_component.ui.dialog.MagnetScreenDialog
import com.xyoye.anime_component.ui.dialog.SearchDomainDialog
import com.xyoye.common_component.adapter.addEmptyView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.addToClipboard
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.focus.applyDpadFocusable
import com.xyoye.common_component.utils.MagnetUtils
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.data.MagnetData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MagnetScreenType

class SearchMagnetFragment :
    BaseFragment<SearchMagnetFragmentViewModel, FragmentSearchMagnetBinding>(),
    SearchListener {
    companion object {
        fun newInstance(searchWord: String?): SearchMagnetFragment {
            val argument = Bundle()
            argument.putString("search_word", searchWord)
            val searchMagnetFragment = SearchMagnetFragment()
            searchMagnetFragment.arguments = argument
            return searchMagnetFragment
        }
    }

    private val actionData = ManageMagnet.values().map { it.toSheetActionBean() }
    private var activeDialog: Dialog? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SearchMagnetFragmentViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.fragment_search_magnet

    override fun initView() {
        initObserver()

        initRv()

        dataBinding.historyLabelsView.setOnLabelClickListener { _, _, position ->
            viewModel.searchHistoryLiveData.value?.let {
                search(it[position])
            }
        }

        dataBinding.historyLabelsView.setOnLabelLongClickListener { _, _, position ->
            viewModel.searchHistoryLiveData.value?.let {
                viewModel.deleteSearchHistory(it[position])
                return@setOnLabelLongClickListener true
            }
            return@setOnLabelLongClickListener false
        }

        dataBinding.domainTv.setOnClickListener {
            viewModel.cancelDialogRequest()
            showInputDomainDialog()
        }

        val isResDomainExist = AppConfig.getMagnetResDomain() != null
        dataBinding.domainTv.setTextColorRes(if (isResDomainExist) R.color.text_theme else R.color.text_red)

        if (viewModel.initializeSearch(arguments?.getString("search_word"))) {
            (mAttachActivity as? SearchActivity)?.onSearch(viewModel.searchText.get().orEmpty())
        }
    }

    private fun initRv() {
        dataBinding.magnetRv.apply {
            layoutManager = vertical()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new ->
                            if (old is MagnetData && new is MagnetData) old.Magnet == new.Magnet else old == new
                        }
                    }
                    addEmptyView(R.layout.layout_empty)

                    addItem<MagnetData, ItemSearchMagnetBinding>(R.layout.item_search_magnet) {
                        initView { data, _, _ ->
                            itemBinding.apply {
                                val time =
                                    data.PublishDate?.run {
                                        val spaceIndex = indexOf(" ")
                                        if (spaceIndex > 0) {
                                            substring(0, spaceIndex)
                                        } else {
                                            this
                                        }
                                    }

                                magnetTitleTv.text = data.Title
                                magnetSizeTv.text = data.FileSize
                                magnetSubgroupTv.text = data.SubgroupName
                                magnetTypeTv.text = data.TypeName
                                magnetTimeTv.text = time

                                contentView.setOnClickListener {
                                    val magnetHash = MagnetUtils.getMagnetHash(data.Magnet)
                                    if (magnetHash.isEmpty()) {
                                        ToastCenter.showError("错误，磁链为空或无法解析")
                                        return@setOnClickListener
                                    }
                                    val magnetLink = "magnet:?xt=urn:btih:$magnetHash"
                                    val library = MediaLibraryEntity.TORRENT.copy(url = magnetLink)
                                    ARouter
                                        .getInstance()
                                        .build(RouteTable.Stream.StorageFile)
                                        .withParcelable("storageLibrary", library)
                                        .navigation()
                                }

                                contentView.setOnLongClickListener {
                                    showActionDialog(data)
                                    return@setOnLongClickListener true
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun initObserver() {
        viewModel.queryState.collectAtStarted(viewLifecycleOwner) { state ->
            dataBinding.historyCl.isVisible = state.keyword.isEmpty()
            dataBinding.magnetRv.isVisible = state.items.isNotEmpty() || (state.loaded && state.error == null)
            dataBinding.magnetRv.setData(state.items)
            dataBinding.queryStatusTv.apply {
                val error = state.filterError ?: state.error
                val retryable = error != null
                text =
                    when {
                        state.filterLoading -> "正在加载筛选…"
                        state.loading -> "正在搜索…"
                        retryable -> "$error\n按确定重试"
                        state.loaded && state.items.isEmpty() -> "未找到结果"
                        else -> ""
                    }
                isVisible = text.isNotEmpty()
                setOnClickListener { viewModel.retry() }
                applyDpadFocusable(retryable, clickable = retryable)
            }
        }

        viewModel.searchHistoryLiveData.observe(viewLifecycleOwner) {
            dataBinding.historyLabelsView.setLabels(it)
        }

        viewModel.dialogRequests.collectAtStarted(viewLifecycleOwner, Lifecycle.State.RESUMED) { request ->
            if (!isVisible || !viewModel.isCurrentDialog(request)) return@collectAtStarted
            activeDialog?.dismiss()
            when (request) {
                is SearchMagnetFragmentViewModel.DialogRequest.Domain -> showInputDomainDialog()
                is SearchMagnetFragmentViewModel.DialogRequest.Filter -> {
                    activeDialog =
                        MagnetScreenDialog(mAttachActivity, request.items, request.type) { entity ->
                            if (viewModel.isCurrentDialog(request)) {
                                if (request.type == MagnetScreenType.TYPE) {
                                    viewModel.magnetTypeId.set(entity.screenId)
                                    viewModel.magnetTypeText.set(entity.screenName)
                                } else {
                                    viewModel.magnetSubgroupId.set(entity.screenId)
                                    viewModel.magnetSubgroupText.set(entity.screenName)
                                }
                                viewModel.search()
                            }
                        }.also { it.show() }
                }
            }
        }
    }

    override fun search(searchText: String) {
        dataBinding.historyCl.isVisible = false
        dataBinding.magnetRv.isVisible = true

        (mAttachActivity as SearchActivity).onSearch(searchText)

        viewModel.searchText.set(searchText)
        viewModel.search()
    }

    override fun onTextClear() {
        viewModel.clearSearch()
        dataBinding.historyCl.isVisible = true
        dataBinding.magnetRv.isVisible = false
    }

    private fun showInputDomainDialog() {
        activeDialog?.dismiss()
        activeDialog =
            SearchDomainDialog(requireActivity()) {
                dataBinding.domainTv.setTextColorRes(R.color.text_theme)
                AppConfig.putMagnetResDomain(it)
                viewModel.onDomainChanged()
            }.also { it.show() }
    }

    override fun onDestroyView() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroyView()
    }

    private fun showActionDialog(data: MagnetData) {
        BottomActionDialog(requireActivity(), actionData) {
            val magnetHash = MagnetUtils.getMagnetHash(data.Magnet)
            if (data.Magnet.isNullOrEmpty()) {
                ToastCenter.showError("磁链信息为空，无法复制")
                return@BottomActionDialog true
            }
            if (it.actionId == ManageMagnet.Copy && magnetHash.isEmpty()) {
                ToastCenter.showError("错误，磁链为空或无法解析")
                return@BottomActionDialog true
            }
            when (it.actionId) {
                ManageMagnet.Copy -> {
                    "magnet:?xt=urn:btih:$magnetHash".addToClipboard()
                    ToastCenter.showSuccess("磁链已复制！")
                }
                ManageMagnet.CopyContent -> {
                    data.Magnet?.addToClipboard()
                    ToastCenter.showSuccess("磁链信息已复制！")
                }
            }
            return@BottomActionDialog true
        }.show()
    }

    private enum class ManageMagnet(
        val title: String,
        val icon: Int
    ) {
        Copy("复制磁链", R.drawable.ic_magnet_copy),
        CopyContent("复制完整磁链", R.drawable.ic_magnet_copy_content);

        fun toSheetActionBean() = SheetActionBean(this, title, icon)
    }
}
