package com.xyoye.storage_component.ui.fragment.storage_file

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.adapter.BaseViewHolderCreator
import com.xyoye.common_component.adapter.addEmptyView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.adapter.setupVerticalAnimation
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.dp
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.isInvalid
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.extension.loadStorageFileCover
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.toFile
import com.xyoye.common_component.extension.toResColor
import com.xyoye.common_component.extension.toResDrawable
import com.xyoye.common_component.extension.toResString
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.danmu
import com.xyoye.common_component.storage.file.subtitle
import com.xyoye.common_component.storage.impl.BilibiliStorage
import com.xyoye.common_component.utils.PlayHistoryUtils
import com.xyoye.common_component.utils.formatDuration
import com.xyoye.common_component.utils.getRecognizableFileName
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.FileManagerDialog
import com.xyoye.core_ui_component.databinding.ItemStorageFolderBinding
import com.xyoye.core_ui_component.databinding.ItemStorageVideoBinding
import com.xyoye.core_ui_component.databinding.ItemStorageVideoTagBinding
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.bean.VideoTagBean
import com.xyoye.data_component.enums.FileManagerAction
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.ItemStoragePagingBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity

/**
 * Created by xyoye on 2023/4/13
 */

class StorageFileAdapter(
    private val activity: StorageFileActivity,
    private val viewModel: StorageFileFragmentViewModel,
    private val onRefreshRequested: () -> Unit,
    private val onLoginRequested: () -> Unit
) {
    private enum class ManageAction(
        val title: String,
        val icon: Int
    ) {
        // TV端暂时关闭投屏
//        SCREENCAST("投屏", com.xyoye.core_ui_component.R.drawable.ic_video_cast),
        BIND_DANMU("手动查找弹幕", com.xyoye.core_ui_component.R.drawable.ic_bind_danmu_manual),
        BIND_SUBTITLE("手动查找字幕", com.xyoye.core_ui_component.R.drawable.ic_bind_subtitle),
        BIND_AUDIO("添加音频文件", com.xyoye.core_ui_component.R.drawable.ic_bind_audio),
        UNBIND_DANMU("移除弹幕绑定", com.xyoye.core_ui_component.R.drawable.ic_unbind_danmu),
        UNBIND_SUBTITLE("移除字幕绑定", com.xyoye.core_ui_component.R.drawable.ic_unbind_subtitle),
        UNBIND_AUDIO("移除音频绑定", com.xyoye.core_ui_component.R.drawable.ic_unbind_subtitle);

        fun toAction() = SheetActionBean(this, title, icon)
    }

    private val tagDecoration = ItemDecorationOrientation(5.dp(), 0, RecyclerView.HORIZONTAL)

    fun create(): BaseAdapter =
        buildAdapter {
            setupVerticalAnimation()

            setupDiffUtil {
                newDataInstance { it }
                areItemsTheSame(isSameStorageFileItem())
                areContentsTheSame(isSameStorageFileContent())
            }

            addEmptyView(R.layout.layout_empty) {
                initEmptyView {
                    val directoryPath = activity.directory?.filePath()
                    val isBilibiliPagedDirectory =
                        activity.storage.library.mediaType == MediaType.BILIBILI_STORAGE &&
                            BilibiliStorage.isBilibiliPagedDirectoryPath(directoryPath)
                    val requiresLogin =
                        isBilibiliPagedDirectory && (activity.storage as? BilibiliStorage)?.isConnected() == false

                    itemBinding.emptyActionTv.isVisible = true

                    if (requiresLogin) {
                        val directoryName =
                            when (directoryPath) {
                                BilibiliStorage.PATH_HISTORY_DIR -> "历史记录"
                                BilibiliStorage.PATH_FOLLOW_LIVE_DIR -> "关注直播"
                                else -> "当前目录"
                            }
                        itemBinding.emptyTv.text = "需要登录才能查看$directoryName"
                        itemBinding.emptyActionTv.text = "扫码登录"
                        itemBinding.emptyActionTv.setOnClickListener { onLoginRequested.invoke() }
                        return@initEmptyView
                    }

                    if (!viewModel.lastListError.isNullOrBlank()) {
                        itemBinding.emptyTv.text = "加载失败，请检查网络后重试"
                        itemBinding.emptyActionTv.text = "重试加载"
                    } else {
                        itemBinding.emptyTv.text = R.string.text_empty_video.toResString()
                        itemBinding.emptyActionTv.text = "刷新"
                    }
                    itemBinding.emptyActionTv.setOnClickListener { onRefreshRequested.invoke() }
                }
            }

            addItem(R.layout.item_storage_folder) {
                checkType { data -> isDirectoryItem(data) }
                initView(directoryItem())
            }
            addItem(R.layout.item_storage_video) {
                checkType { data -> isVideoItem(data) }
                initView(videoItem())
            }
            addItem(R.layout.item_storage_paging) {
                checkType { data -> data is StoragePagingItem }
                initView(pagingItem())
            }
        }

    private fun isSameStorageFileItem() =
        { old: Any, new: Any ->
            when {
                old is StorageFile && new is StorageFile -> old.uniqueKey() == new.uniqueKey()
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

    private fun isDirectoryItem(data: Any) = data is StorageFile && data.isDirectory()

    private fun isVideoItem(data: Any) = data is StorageFile && data.isFile()

    private fun BaseViewHolderCreator<ItemStorageFolderBinding>.directoryItem() =
        { data: StorageFile ->
            val childFileCount = data.childFileCount()
            val fileCount =
                if (childFileCount > 0) {
                    "${childFileCount}文件"
                } else {
                    "目录"
                }
            itemBinding.folderTv.text = getRecognizableFileName(data)
            itemBinding.folderTv.setTextColor(getTitleColor(data))
            itemBinding.fileCountTv.text = fileCount
            itemBinding.itemLayout.setOnClickListener {
                activity.openDirectory(data)
            }
        }

    private fun BaseViewHolderCreator<ItemStorageVideoBinding>.videoItem() =
        { data: StorageFile ->
            itemBinding.run {
                coverIv.loadStorageFileCover(data)

                titleTv.text = data.fileName()
                titleTv.setTextColor(getTitleColor(data))

                val duration = getDuration(data)
                durationTv.text = duration
                durationTv.isVisible = duration.isNotEmpty()

                setupVideoTag(tagRv, data)

                itemLayout.setOnClickListener {
                    activity.openFile(data)
                }

                itemLayout.setOnLongClickListener {
                    showMoreAction(data, createShareOptions(itemLayout))
                    return@setOnLongClickListener true
                }

                moreActionIv.setOnClickListener {
                    showMoreAction(data, createShareOptions(itemLayout))
                }
            }
        }

    private fun BaseViewHolderCreator<ItemStoragePagingBinding>.pagingItem() =
        { data: StoragePagingItem ->
            val isDataEmpty = data.isDataEmpty
            val emptyTitle =
                when (activity.directory?.filePath()) {
                    BilibiliStorage.PATH_FOLLOW_LIVE_DIR -> "暂无直播"
                    else -> "暂无历史记录"
                }
            val title =
                when (data.state) {
                    com.xyoye.common_component.storage.PagedStorage.State.LOADING -> "加载中…"
                    com.xyoye.common_component.storage.PagedStorage.State.ERROR -> "加载失败，按确认键重试"
                    com.xyoye.common_component.storage.PagedStorage.State.NO_MORE ->
                        if (isDataEmpty) emptyTitle else "没有更多了"
                    com.xyoye.common_component.storage.PagedStorage.State.IDLE ->
                        when {
                            data.hasMore && isDataEmpty -> emptyTitle
                            data.hasMore -> "加载更多"
                            isDataEmpty -> emptyTitle
                            else -> "没有更多了"
                        }
                }
            val subtitle =
                when (data.state) {
                    com.xyoye.common_component.storage.PagedStorage.State.ERROR ->
                        if (isDataEmpty) "请检查网络后重试" else "弱网/断网时可稍后重试"
                    com.xyoye.common_component.storage.PagedStorage.State.IDLE ->
                        if (data.hasMore && isDataEmpty) "按确认键加载更多" else ""
                    com.xyoye.common_component.storage.PagedStorage.State.NO_MORE ->
                        if (isDataEmpty) "按确认键刷新" else ""
                    else -> ""
                }

            itemBinding.titleTv.text = title
            itemBinding.subtitleTv.text = subtitle
            itemBinding.subtitleTv.isVisible = subtitle.isNotBlank()
            itemBinding.loadingPb.isVisible = data.state == com.xyoye.common_component.storage.PagedStorage.State.LOADING

            itemBinding.itemLayout.setOnClickListener {
                when {
                    data.state == com.xyoye.common_component.storage.PagedStorage.State.LOADING -> Unit
                    data.state == com.xyoye.common_component.storage.PagedStorage.State.ERROR -> {
                        viewModel.loadMore(showFailureToast = !activity.isTelevisionUiMode())
                    }

                    data.state == com.xyoye.common_component.storage.PagedStorage.State.IDLE && data.hasMore -> {
                        viewModel.loadMore(showFailureToast = !activity.isTelevisionUiMode())
                    }

                    data.isDataEmpty -> onRefreshRequested.invoke()
                    else -> ToastCenter.showInfo("没有更多了")
                }
            }
        }

    private fun setupVideoTag(
        tagRv: RecyclerView,
        data: StorageFile
    ) {
        tagRv.apply {
            layoutManager = horizontal()
            adapter =
                buildAdapter {
                    addItem(R.layout.item_storage_video_tag) { initView(tagItem()) }
                }
            removeItemDecoration(tagDecoration)
            addItemDecoration(tagDecoration)
            setData(generateVideoTags(data))
        }
    }

    private fun BaseViewHolderCreator<ItemStorageVideoTagBinding>.tagItem() =
        { data: VideoTagBean ->
            val background = R.drawable.background_video_tag.toResDrawable()
            background?.colorFilter = PorterDuffColorFilter(data.color, PorterDuff.Mode.SRC)
            itemBinding.textView.background = background
            itemBinding.textView.text = data.tag
        }

    private fun generateVideoTags(data: StorageFile): List<VideoTagBean> {
        val tagList = mutableListOf<VideoTagBean>()
        if (isShowDanmu(data)) {
            tagList.add(VideoTagBean("弹幕", R.color.theme.toResColor()))
        }
        if (isShowSubtitle(data)) {
            tagList.add(VideoTagBean("字幕", R.color.orange.toResColor()))
        }
        if (isShowAudio(data)) {
            tagList.add(VideoTagBean("音频", R.color.pink.toResColor()))
        }
        val progress = getProgress(data)
        if (progress.isNotEmpty()) {
            tagList.add(VideoTagBean(progress, R.color.black_alpha.toResColor()))
        }
        val lastPlayTime = getPlayTime(data)
        if (lastPlayTime.isNotEmpty()) {
            tagList.add(VideoTagBean(lastPlayTime, R.color.black_alpha.toResColor()))
        }
        return tagList
    }

    private fun getTitleColor(file: StorageFile): Int =
        when (file.playHistory?.isLastPlay == true) {
            true ->
                com.xyoye.core_ui_component.R.color.text_theme
                    .toResColor(activity)
            else ->
                com.xyoye.core_ui_component.R.color.text_black
                    .toResColor(activity)
        }

    private fun getProgress(file: StorageFile): String {
        val position = file.playHistory?.videoPosition ?: 0
        val duration = file.playHistory?.videoDuration ?: 0
        if (position == 0L || duration == 0L) {
            return ""
        }

        var progress = (position * 100f / duration).toInt()
        if (progress == 0) {
            progress = 1
        }
        return "进度 $progress%"
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

    private fun getPlayTime(file: StorageFile): String {
        // Url为空，意味着该历史记录为资源绑定记录，非播放记录
        if (TextUtils.isEmpty(file.playHistory?.url)) {
            return ""
        }
        return file.playHistory?.playTime?.run {
            PlayHistoryUtils.formatPlayTime(this)
        } ?: ""
    }

    private fun isShowDanmu(file: StorageFile): Boolean = file.playHistory?.danmuPath?.isNotEmpty() == true

    private fun isShowSubtitle(file: StorageFile): Boolean = file.playHistory?.subtitlePath?.isNotEmpty() == true

    private fun isShowAudio(file: StorageFile): Boolean = file.playHistory?.audioPath?.isNotEmpty() == true

    private fun showMoreAction(
        file: StorageFile,
        options: ActivityOptionsCompat
    ) {
        BottomActionDialog(activity, getMoreActions(file)) {
            when (it.actionId) {
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
        }.show()
    }

    private fun getMoreActions(file: StorageFile) =
        mutableListOf<SheetActionBean>().apply {
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
