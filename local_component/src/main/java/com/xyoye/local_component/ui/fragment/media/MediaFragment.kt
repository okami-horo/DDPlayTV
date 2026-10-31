package com.xyoye.local_component.ui.fragment.media

import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.application.DanDanPlay
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.deletable
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.utils.view.ItemDecorationSpace
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.FragmentMediaBinding
import com.xyoye.local_component.databinding.ItemMediaLibraryBinding
import com.xyoye.local_component.ui.metadata.MetadataCard
import com.xyoye.local_component.ui.metadata.MetadataCardAdapter
import com.xyoye.local_component.ui.metadata.MetadataLibraryViewModel

/**
 * Created by xyoye on 2020/7/27.
 */

@Route(path = RouteTable.Local.MediaFragment)
class MediaFragment :
    BaseFragment<MediaViewModel, FragmentMediaBinding>(),
    DpadFocusOwner {
    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            MediaViewModel::class.java
        )

    override fun getLayoutId() = R.layout.fragment_media

    override fun initView() {
        ARouter.getInstance().inject(this)

        viewModel.initLocalStorage()

        initRv()

        dataBinding.addMediaStorageBt.setOnClickListener {
            showAddStorageDialog()
        }

        viewModel.mediaLibWithStatusLiveData.observe(viewLifecycleOwner) {
            dataBinding.mediaLibRv.setData(it)
        }

        val metadata = ViewModelProvider(this)[MetadataLibraryViewModel::class.java]
        val cards =
            MetadataCardAdapter(landscape = true, click = { card ->
                ARouter
                    .getInstance()
                    .build(RouteTable.Local.MetadataDetail)
                    .withString("kind", card.key.substringBefore(':'))
                    .withLong("tmdbId", card.key.substringAfter(':').toLong())
                    .navigation()
            })
        dataBinding.libraryCards.apply {
            layoutManager = grid(3)
            adapter = cards
            setFocusOutAllowed(true, true)
        }
        metadata.state.collectAtStarted(viewLifecycleOwner) { state ->
            val bindings = state.bindings.associateBy { it.assetId }
            val order =
                state.assets
                    .sortedByDescending { it.discoveredAt }
                    .mapNotNull { asset ->
                        bindings[asset.assetId]?.let { "${it.mediaKind}:${it.tmdbId}" }
                    }.distinct()
                    .withIndex()
                    .associate { it.value to it.index }
            val historyByFile = state.history.associateBy { "${it.storageId}:${it.uniqueKey}" }
            val progress =
                state.assets
                    .mapNotNull { asset ->
                        val history = historyByFile["${asset.libraryId}:${asset.uniqueKey}"] ?: return@mapNotNull null
                        val media = bindings[asset.assetId] ?: return@mapNotNull null
                        "${media.mediaKind}:${media.tmdbId}" to
                            if (history.videoDuration > 0) (history.videoPosition * 100 / history.videoDuration).toInt() else 0
                    }.toMap()
            cards.submitList(
                state.works.sortedBy { order["${it.mediaKind}:${it.tmdbId}"] ?: Int.MAX_VALUE }.map {
                    MetadataCard(
                        "${it.mediaKind}:${it.tmdbId}",
                        it.title,
                        "${it.releaseDate.take(4)} · ${if (it.mediaKind == "tv") "剧集 / 番剧" else "电影"}",
                        it.backdrop ?: it.poster,
                        progress["${it.mediaKind}:${it.tmdbId}"]
                    )
                }
            )
            dataBinding.libraryCount.text = if (state.works.isEmpty()) "还没有已入库作品。打开来源目录，选择识别或扫描目录。" else "${state.works.size} 部作品 · 按入库时间排列"
            dataBinding.libraryCards.isVisible = state.works.isNotEmpty()
            dataBinding.libraryCount.isVisible = state.works.isEmpty()
        }
        dataBinding.showAll.setOnClickListener { ARouter.getInstance().build(RouteTable.Local.MetadataLibrary).navigation() }
        dataBinding.metadataTasks.setOnClickListener { ARouter.getInstance().build(RouteTable.User.MetadataSettings).navigation() }
    }

    private fun initRv() {
        dataBinding.mediaLibRv.apply {
            layoutManager = horizontal()
            addItemDecoration(ItemDecorationSpace((6 * resources.displayMetrics.density).toInt(), 0))

            adapter =
                buildAdapter {
                    stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                    setupDiffUtil {
                        areItemsTheSame { old, new ->
                            old as MediaLibraryEntity
                            new as MediaLibraryEntity
                            old.id == new.id
                        }
                    }
                    addItem<MediaLibraryEntity, ItemMediaLibraryBinding>(R.layout.item_media_library) {
                        initView { data, _, _ ->
                            itemBinding.apply {
                                libraryNameTv.text = data.displayName
                                libraryUrlTv.text =
                                    when {
                                        data.mediaType == MediaType.LOCAL_STORAGE -> "设备媒体"
                                        !data.mediaType.deletable -> "播放记录"
                                        else -> data.disPlayDescribe
                                    }
                                libraryCoverIv.setImageResource(
                                    if (data.mediaType ==
                                        MediaType.LOCAL_STORAGE
                                    ) {
                                        R.drawable.cinema_ic_drive
                                    } else {
                                        R.drawable.cinema_ic_folder
                                    }
                                )

                                itemLayout.setOnClickListener {
                                    if (data.mediaType == MediaType.LOCAL_STORAGE) {
                                        DanDanPlay.permission.storage.request(this@MediaFragment) {
                                            onGranted {
                                                launchMediaStorage(data)
                                            }
                                            onDenied {
                                                ToastCenter.showError("获取文件读取权限失败，无法打开本地媒体库")
                                            }
                                        }
                                    } else {
                                        launchMediaStorage(data)
                                    }
                                }
                                itemLayout.setOnLongClickListener {
                                    if (data.mediaType.deletable) {
                                        showManageStorageDialog(data)
                                    }
                                    true
                                }
                            }
                        }
                    }
                }

            (this as? DpadRecyclerView)?.apply {
                setFocusOutAllowed(true, true)
                setOnKeyInterceptListener(
                    object : DpadRecyclerView.OnKeyInterceptListener {
                        override fun onInterceptKeyEvent(event: KeyEvent): Boolean {
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                if (event.action == KeyEvent.ACTION_DOWN) dataBinding.metadataTasks.requestFocus()
                                return true
                            }
                            if (event.keyCode != KeyEvent.KEYCODE_MENU && event.keyCode != KeyEvent.KEYCODE_SETTINGS) {
                                return false
                            }
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showAddStorageDialog()
                            return true
                        }
                    }
                )
            }
        }
    }

    override fun requestDefaultDpadFocus(): Boolean {
        val binding = bindingOrNull ?: return false
        if (!isVisible || binding.root.isInTouchMode) return false
        return if ((binding.mediaLibRv.adapter?.itemCount ?: 0) > 0) {
            binding.mediaLibRv.requestFocus()
        } else {
            binding.addMediaStorageBt.requestFocus()
        }
    }

    override fun onDestroyView() {
        bindingOrNull?.libraryCards?.adapter = null
        super.onDestroyView()
    }

    private fun launchMediaStorage(data: MediaLibraryEntity) {
        when (data.mediaType) {
            MediaType.STREAM_LINK -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Local.PlayHistory)
                    .withSerializable("typeValue", data.mediaType.value)
                    .navigation()
            }
            MediaType.MAGNET_LINK -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Local.PlayHistory)
                    .withSerializable("typeValue", data.mediaType.value)
                    .navigation()
            }
            MediaType.OTHER_STORAGE -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Local.PlayHistory)
                    .withSerializable("typeValue", data.mediaType.value)
                    .navigation()
            }

            MediaType.SCREEN_CAST -> {
                ToastCenter.showWarning("电视端不支持发起投屏发送")
            }

            MediaType.LOCAL_STORAGE,
            MediaType.FTP_SERVER,
            MediaType.SMB_SERVER,
            MediaType.WEBDAV_SERVER,
            MediaType.REMOTE_STORAGE,
            MediaType.EXTERNAL_STORAGE,
            MediaType.ALSIT_STORAGE,
            MediaType.BAIDU_PAN_STORAGE,
            MediaType.OPEN_115_STORAGE,
            MediaType.CLOUD_115_STORAGE -> {
                ARouter
                    .getInstance()
                    .build(RouteTable.Stream.StorageFile)
                    .withParcelable("storageLibrary", data)
                    .navigation()
            }
        }
    }

    private fun showAddStorageDialog() {
        val actionList =
            MediaType
                .values()
                .filter { it.deletable && it != MediaType.SCREEN_CAST }
                .map { it.toAction() }

        BottomActionDialog(
            requireActivity(),
            actionList,
            "新增网络媒体库"
        ) {
            val mediaType = it.actionId as MediaType
            ARouter
                .getInstance()
                .build(RouteTable.Stream.StoragePlus)
                .withSerializable("mediaType", mediaType)
                .navigation()
            return@BottomActionDialog true
        }.show()
    }

    private fun showManageStorageDialog(data: MediaLibraryEntity) {
        val actions = mutableListOf<SheetActionBean>()
        actions.add(ManageStorage.Edit.toAction())
        actions.add(ManageStorage.Delete.toAction())

        BottomActionDialog(requireActivity(), actions) {
            if (it.actionId == ManageStorage.Edit) {
                ARouter
                    .getInstance()
                    .build(RouteTable.Stream.StoragePlus)
                    .withSerializable("mediaType", data.mediaType)
                    .withParcelable("editData", data)
                    .navigation()
            } else if (it.actionId == ManageStorage.Delete) {
                showDeleteStorageDialog(data)
            }
            return@BottomActionDialog true
        }.show()
    }

    private fun showDeleteStorageDialog(data: MediaLibraryEntity) {
        CommonDialog
            .Builder(requireActivity())
            .apply {
                content = "确认删除以下媒体库?\n\n${data.displayName}"
                positiveText = "确认"
                addPositive { dialog ->
                    dialog.dismiss()
                    viewModel.deleteStorage(data)
                }
                addNegative()
            }.build()
            .show()
    }

    private enum class ManageStorage(
        val title: String,
        val icon: Int
    ) {
        Edit("编辑媒体库", R.drawable.ic_edit_storage),
        Delete("删除媒体库", R.drawable.ic_delete_storage);

        fun toAction() = SheetActionBean(this, title, icon)
    }
}
