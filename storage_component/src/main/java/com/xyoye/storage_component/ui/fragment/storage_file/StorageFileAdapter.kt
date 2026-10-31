package com.xyoye.storage_component.ui.fragment.storage_file

import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair
import androidx.core.view.isVisible
import coil.dispose
import coil.load
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.adapter.BaseViewHolderCreator
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.dp
import com.xyoye.common_component.extension.isInvalid
import com.xyoye.common_component.extension.toFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.danmu
import com.xyoye.common_component.storage.file.subtitle
import com.xyoye.common_component.utils.formatDuration
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.FileManagerDialog
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.enums.FileManagerAction
import com.xyoye.data_component.enums.TrackType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.ItemFileBrowserBinding
import com.xyoye.storage_component.databinding.ItemStoragePagingBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity

/**
 * Created by xyoye on 2023/4/13
 */

class StorageFileAdapter(
    private val activity: StorageFileActivity,
    private val viewModel: StorageFileFragmentViewModel,
    private val onFileFocused: (StorageFile, Boolean) -> Unit
) {
    private enum class ManageAction(
        val title: String,
        val icon: Int
    ) {
        // TV端暂时关闭投屏
//        SCREENCAST("投屏", com.xyoye.core_ui_component.R.drawable.ic_video_cast),
        OPEN("打开", com.xyoye.core_ui_component.R.drawable.cinema_ic_play),
        METADATA("识别 / 更正资料", com.xyoye.core_ui_component.R.drawable.ic_bind_danmu_manual),
        BIND_DANMU("手动查找弹幕", com.xyoye.core_ui_component.R.drawable.ic_bind_danmu_manual),
        BIND_SUBTITLE("手动查找字幕", com.xyoye.core_ui_component.R.drawable.ic_bind_subtitle),
        BIND_AUDIO("添加音频文件", com.xyoye.core_ui_component.R.drawable.ic_bind_audio),
        UNBIND_DANMU("移除弹幕绑定", com.xyoye.core_ui_component.R.drawable.ic_unbind_danmu),
        UNBIND_SUBTITLE("移除字幕绑定", com.xyoye.core_ui_component.R.drawable.ic_unbind_subtitle),
        UNBIND_AUDIO("移除音频绑定", com.xyoye.core_ui_component.R.drawable.ic_unbind_subtitle);

        fun toAction() = SheetActionBean(this, title, icon)
    }

    fun create(): BaseAdapter =
        buildAdapter {
            setupDiffUtil {
                newDataInstance { it }
                areItemsTheSame(isSameStorageFileItem())
                areContentsTheSame(isSameStorageFileContent())
            }

            addItem<StorageFile, ItemFileBrowserBinding>(R.layout.item_file_browser) {
                checkType { it is StorageFile }
                initView(fileItem())
            }
            addItem(R.layout.item_storage_paging) {
                checkType { data -> data is StoragePagingItem }
                initView(pagingItem())
            }
        }

    private fun isSameStorageFileItem() =
        { old: Any, new: Any ->
            when {
                old is StorageFile && new is StorageFile -> old.browserKey() == new.browserKey()
                old is StoragePagingItem && new is StoragePagingItem -> true
                else -> false
            }
        }

    private fun isSameStorageFileContent() =
        { old: Any, new: Any ->
            when {
                old is StorageFile && new is StorageFile -> {
                    old.fileUrl() == new.fileUrl() &&
                        old.fileName() == new.fileName() &&
                        old.childFileCount() == new.childFileCount() &&
                        old.playHistory == new.playHistory &&
                        old.playHistory?.isLastPlay == new.playHistory?.isLastPlay
                }

                old is StoragePagingItem && new is StoragePagingItem -> {
                    old.state == new.state && old.hasMore == new.hasMore && old.isDataEmpty == new.isDataEmpty
                }

                else -> false
            }
        }

    private fun BaseViewHolderCreator<ItemFileBrowserBinding>.fileItem() =
        { data: StorageFile ->
            itemBinding.run {
                val folder = data.isDirectory()
                itemLayout.layoutParams = itemLayout.layoutParams.apply { height = (if (folder) 43 else 52).dp() }
                coverIv.layoutParams = coverIv.layoutParams.apply { width = (if (folder) 34 else 51).dp() }
                coverIv.dispose()
                if (folder) {
                    coverIv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    coverIv.setImageResource(R.drawable.file_browser_folder)
                    coverIv.setPadding(7.dp(), 7.dp(), 7.dp(), 7.dp())
                } else {
                    coverIv.scaleType = ImageView.ScaleType.CENTER_CROP
                    coverIv.setPadding(0, 0, 0, 0)
                    coverIv.load(data.fileCover()) {
                        placeholder(R.drawable.file_browser_placeholder)
                        fallback(R.drawable.file_browser_placeholder)
                        error(R.drawable.file_browser_placeholder)
                    }
                }
                titleTv.text = data.fileName()
                detailsTv.text = fileBrowserDetails(activity, data)
                detailsTv.isVisible = !folder && detailsTv.text.isNotEmpty()
                durationTv.text = getDuration(data)
                durationTv.isVisible = !folder && durationTv.text.isNotEmpty()
                moreActionIv.setImageResource(if (folder) R.drawable.cinema_ic_next else R.drawable.file_browser_more)
                moreActionIv.isFocusable = !folder
                moreActionIv.isClickable = !folder
                itemLayout.nextFocusRightId = if (folder) itemLayout.id else moreActionIv.id
                itemLayout.setOnFocusChangeListener { _, focused -> if (focused) onFileFocused(data, false) }
                moreActionIv.setOnFocusChangeListener { _, focused -> if (focused) onFileFocused(data, true) }
                itemLayout.setOnClickListener { if (folder) activity.openDirectory(data) else activity.openFile(data) }
                itemLayout.setOnLongClickListener {
                    showMoreAction(data, itemLayout)
                    true
                }
                moreActionIv.setOnClickListener { showMoreAction(data, itemLayout) }
            }
        }

    private fun BaseViewHolderCreator<ItemStoragePagingBinding>.pagingItem() =
        { data: StoragePagingItem ->
            itemBinding.titleTv.text =
                when (data.state) {
                    com.xyoye.common_component.storage.PagedStorage.State.LOADING -> "正在加载下一页…"
                    com.xyoye.common_component.storage.PagedStorage.State.ERROR -> "加载失败 · 重试"
                    else -> if (data.hasMore) "加载更多" else "已全部显示"
                }
            itemBinding.loadingPb.isVisible = data.state == com.xyoye.common_component.storage.PagedStorage.State.LOADING

            val actionable =
                data.state == com.xyoye.common_component.storage.PagedStorage.State.ERROR ||
                    (data.state == com.xyoye.common_component.storage.PagedStorage.State.IDLE && data.hasMore)
            // Keep the in-flight caller stable, but never focus a terminal status.
            val focusable = actionable || data.state == com.xyoye.common_component.storage.PagedStorage.State.LOADING
            itemBinding.itemLayout.isFocusable = focusable
            itemBinding.itemLayout.isClickable = actionable
            itemBinding.itemLayout.setBackgroundResource(if (focusable) R.drawable.file_browser_row else android.R.color.transparent)
            itemBinding.itemLayout.setOnClickListener(if (actionable) android.view.View.OnClickListener { viewModel.loadMore() } else null)
        }

    private fun getDuration(file: StorageFile): String {
        val position = file.playHistory?.videoPosition ?: 0
        var duration = file.playHistory?.videoDuration ?: 0
        if (duration == 0L) {
            duration = file.videoDuration()
        }
        return if (position > 0 && duration > 0) {
            "${formatDuration(position)}/${formatDuration(duration)}"
        } else if (duration > 0) {
            formatDuration(duration)
        } else {
            ""
        }
    }

    fun showMoreAction(
        file: StorageFile,
        itemLayout: ConstraintLayout
    ) {
        val caller = activity.currentFocus
        val options = createShareOptions(itemLayout)
        BottomActionDialog(activity, getMoreActions(file), "文件操作") {
            when (it.actionId) {
                ManageAction.OPEN -> if (file.isDirectory()) activity.openDirectory(file) else activity.openFile(file)
                ManageAction.METADATA -> {
                    val reference =
                        com.xyoye.common_component.storage.catalog.CatalogSourceReader
                            .capture(file)
                    ARouter
                        .getInstance()
                        .build(RouteTable.Local.MetadataMatch)
                        .withInt("libraryId", file.storage.library.id)
                        .withString("uniqueKey", reference.uniqueKey)
                        .withString("storagePath", reference.storagePath)
                        .withString("protocolReference", reference.protocolReference)
                        .navigation(activity)
                }
                ManageAction.BIND_DANMU -> bindExtraSource(file, true, options)
                ManageAction.BIND_SUBTITLE -> bindExtraSource(file, false, options)
                ManageAction.BIND_AUDIO -> bindAudioSource(file)
                ManageAction.UNBIND_DANMU -> viewModel.unbindExtraSource(file, TrackType.DANMU)
                ManageAction.UNBIND_SUBTITLE -> viewModel.unbindExtraSource(file, TrackType.SUBTITLE)
                ManageAction.UNBIND_AUDIO -> viewModel.unbindExtraSource(file, TrackType.AUDIO)
                // TV端不再提供投屏
//                ManageAction.SCREENCAST -> activity.castFile(file)
            }
            return@BottomActionDialog true
        }.apply {
            setOnDismissListener { if (caller?.isShown == true) caller.requestFocus() }
        }.show()
    }

    private fun getMoreActions(file: StorageFile) =
        mutableListOf<SheetActionBean>().apply {
            add(
                SheetActionBean(
                    ManageAction.OPEN,
                    file.fileName(),
                    if (file.isDirectory()) R.drawable.cinema_ic_folder else R.drawable.cinema_ic_play,
                    if (file.isDirectory()) "打开文件夹" else "播放视频"
                )
            )
            if (file.isDirectory()) return@apply
            if (com.xyoye.common_component.storage.catalog.CatalogSourceReader.supports(
                    file.storage.library.mediaType
                )
            ) {
                add(ManageAction.METADATA.toAction())
            }
            // TV端不再提供投屏
//            add(ManageAction.SCREENCAST.toAction())
            add(ManageAction.BIND_DANMU.toAction())
            add(ManageAction.BIND_SUBTITLE.toAction())
            add(ManageAction.BIND_AUDIO.toAction())
            if (file.danmu != null) {
                add(ManageAction.UNBIND_DANMU.toAction())
            }
            if (file.subtitle != null) {
                add(ManageAction.UNBIND_SUBTITLE.toAction())
            }
            if (file.playHistory?.audioPath != null) {
                add(ManageAction.UNBIND_AUDIO.toAction())
            }
        }

    private fun bindExtraSource(
        file: StorageFile,
        bindDanmu: Boolean,
        options: ActivityOptionsCompat
    ) {
        activity.shareStorageFile = file
        ARouter
            .getInstance()
            .build(RouteTable.Local.BindExtraSource)
            .withBoolean("isSearchDanmu", bindDanmu)
            .withOptionsCompat(options)
            .navigation(activity)
    }

    private fun createShareOptions(itemLayout: ConstraintLayout) =
        ActivityOptionsCompat.makeSceneTransitionAnimation(
            activity,
            Pair(itemLayout, itemLayout.transitionName),
        )

    private fun bindAudioSource(file: StorageFile) {
        FileManagerDialog(
            activity,
            FileManagerAction.ACTION_SELECT_AUDIO,
        ) {
            if (it.toFile().isInvalid()) {
                ToastCenter.showError("绑定音频失败，音频不存在或内容为空")
                return@FileManagerDialog
            }
            viewModel.bindAudioSource(file, it)
        }.show()
    }
}
