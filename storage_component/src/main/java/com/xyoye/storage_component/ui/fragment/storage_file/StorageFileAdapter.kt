package com.xyoye.storage_component.ui.fragment.storage_file

import android.widget.ImageView
import androidx.core.view.isVisible
import coil.dispose
import coil.load
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.adapter.BaseViewHolderCreator
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.dp
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.formatDuration
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
    private val onFileFocused: (StorageFile) -> Unit
) {
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
                directoryArrow.isVisible = folder
                itemLayout.setOnFocusChangeListener { _, focused -> if (focused) onFileFocused(data) }
                itemLayout.setOnClickListener { if (folder) activity.openDirectory(data) else activity.openFile(data) }
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
}
