package com.xyoye.player.controller.setting

import android.content.Context
import android.os.Environment
import android.util.AttributeSet
import android.view.Gravity
import androidx.annotation.Dimension
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.extension.filterHiddenFile
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.isValid
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.extension.toResDrawable
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.common_component.utils.comparator.FileNameComparator
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.getFileName
import com.xyoye.common_component.utils.getFolderName
import com.xyoye.common_component.utils.isAudioFile
import com.xyoye.common_component.utils.isDanmuFile
import com.xyoye.common_component.utils.isSubtitleFile
import com.xyoye.common_component.utils.isVideoFile
import com.xyoye.common_component.utils.view.FilePathItemDecoration
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.bean.FileManagerBean
import com.xyoye.data_component.bean.FilePathBean
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemFileManagerFolderBinding
import com.xyoye.player_component.databinding.ItemFileManagerPlayerBinding
import com.xyoye.player_component.databinding.LayoutSwitchSourceBinding
import java.io.File

/**
 * Created by xyoye on 2021/2/15.
 */

class SwitchSourceView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSwitchSourceBinding>(context, attrs, defStyleAttr) {
    private val mRootPath = Environment.getExternalStorageDirectory().absolutePath

    private val mPathData = mutableListOf<FilePathBean>()
    private val mCommonDirectoryData = mutableListOf<FilePathBean>()
    private val mFileData = mutableListOf<FileManagerBean>()

    private var mTrackType = TrackType.DANMU

    // 标题
    private val title
        get() =
            when (mTrackType) {
                TrackType.AUDIO -> "选择音轨文件"
                TrackType.DANMU -> "选择弹幕轨文件"
                TrackType.SUBTITLE -> "选择字幕轨文件"
                TrackType.VIDEO -> "选择视频轨文件"
            }

    // 是否显示搜索网络弹幕按钮
    private val sourceSearchAble get() = mTrackType == TrackType.DANMU

    // 文件图标
    private val sourceFileIcon
        get() =
            when (mTrackType) {
                TrackType.AUDIO -> R.drawable.ic_file_audio
                TrackType.DANMU -> R.drawable.ic_file_xml
                TrackType.SUBTITLE -> R.drawable.ic_file_subtitle
                TrackType.VIDEO -> R.drawable.ic_file_video
            }

    init {
        initView()

        initListener()
    }

    override fun getLayoutId() = R.layout.layout_switch_source

    override fun getSettingViewType() = SettingViewType.SWITCH_SOURCE

    override fun getGravity() = Gravity.START

    override fun onViewShow() {
        viewBinding.titleTv.text = title
        openDirectory(getDefaultOpenDirectory())

        mCommonDirectoryData.clear()
        mCommonDirectoryData.addAll(getCommonDirectoryList())
        viewBinding.rvCommonFolder.setData(mCommonDirectoryData)

        viewBinding.tvSearchNetworkDanmu.isVisible = sourceSearchAble
    }

    fun setTrackType(trackType: TrackType) {
        this.mTrackType = trackType
    }

    private fun initView() {
        viewBinding.rvCommonFolder.apply {
            layoutManager = horizontal()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new -> (old as FilePathBean).path == (new as FilePathBean).path }
                    }
                    addItem<FilePathBean, ItemFileManagerFolderBinding>(R.layout.item_file_manager_folder) {
                        initView { data, _, _ ->
                            itemBinding.apply {
                                tvName.background =
                                    R.drawable.background_player_setting_text.toResDrawable()
                                tvName.text = data.name
                                tvName.setOnClickListener {
                                    openDirectory(data.path)
                                }
                            }
                        }
                    }
                }

            addItemDecoration(
                ItemDecorationOrientation(
                    dp2px(5),
                    0,
                    RecyclerView.HORIZONTAL,
                ),
            )
        }

        viewBinding.rvPath.apply {
            layoutManager = horizontal()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new -> (old as FilePathBean).path == (new as FilePathBean).path }
                    }
                    addItem<FilePathBean, ItemFileManagerFolderBinding>(R.layout.item_file_manager_folder) {
                        initView { data, _, _ ->
                            itemBinding.apply {
                                tvName.background =
                                    R.drawable.background_player_setting_text_transparent.toResDrawable()
                                tvName.text = data.name
                                tvName.setTextSize(Dimension.SP, 14f)
                                tvName.setTextColorRes(
                                    if (data.isOpened) R.color.text_white_immutable else R.color.text_gray,
                                )
                                tvName.setOnClickListener {
                                    openDirectory(data.path)
                                }
                            }
                        }
                    }
                }

            val dividerSize = dp2px(16)
            val divider = R.drawable.ic_file_manager_arrow.toResDrawable()
            if (divider != null) {
                addItemDecoration(FilePathItemDecoration(divider, dividerSize))
            }

            itemAnimator = null

            setData(mPathData)
        }

        viewBinding.rvFile.apply {
            layoutManager = vertical()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new -> (old as FileManagerBean).filePath == (new as FileManagerBean).filePath }
                    }
                    addItem<FileManagerBean, ItemFileManagerPlayerBinding>(R.layout.item_file_manager_player) {
                        initView { data, _, _ ->
                            itemBinding.apply {
                                fileNameTv.text = data.fileName
                                fileIv.setImageResource(if (data.isDirectory) R.drawable.ic_folder else sourceFileIcon)
                                itemLayout.setOnClickListener {
                                    when {
                                        data.isDirectory -> {
                                            openDirectory(data.filePath)
                                        }

                                        else -> {
                                            openFile(data)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun initListener() {
        viewBinding.tvSearchNetworkDanmu.setOnClickListener {
            mControlWrapper.showSettingView(SettingViewType.SEARCH_DANMU)
        }
    }

    /**
     * 选中目标文件
     */
    private fun openFile(data: FileManagerBean) {
        mControlWrapper.hideSettingView()

        File(data.filePath).parentFile?.absolutePath?.let {
            AppConfig.putLastOpenFolder(it)
        }

        when (mTrackType) {
            TrackType.AUDIO -> {
                mControlWrapper.addTrack(VideoTrackBean.audio(data.filePath))
            }

            TrackType.DANMU -> {
                mControlWrapper.addTrack(VideoTrackBean.danmu(LocalDanmuBean(data.filePath)))
            }

            TrackType.SUBTITLE -> {
                mControlWrapper.addTrack(VideoTrackBean.subtitle(data.filePath))
            }

            TrackType.VIDEO -> Unit
        }
    }

    /**
     * 打开文件夹
     */
    private fun openDirectory(directoryPath: String) {
        val directory = File(directoryPath)
        val pathData = getPathData(directory.absolutePath)
        if (pathData.isNotEmpty()) {
            pathData.last().isOpened = true
        }
        mPathData.clear()
        mPathData.addAll(pathData)
        viewBinding.rvPath.setData(mPathData)
        val paths = viewBinding.rvPath
        if (paths is DpadRecyclerView) {
            paths.setSelectedPosition(mPathData.lastIndex)
        } else {
            paths.scrollToPosition(mPathData.lastIndex)
        }

        mFileData.clear()
        mFileData.addAll(getDirectoryChildData(directory))
        viewBinding.rvFile.setData(mFileData)
        if (isSettingShowing() && viewBinding.rvFile.hasFocus() && mFileData.isEmpty()) {
            viewBinding.rvPath.requestFocus()
        }
    }

    /**
     * 获取文件夹名称列表
     */
    private fun getPathData(directoryPath: String): List<FilePathBean> {
        if (directoryPath.isEmpty()) {
            return emptyList()
        }
        val pathData = mutableListOf<FilePathBean>()
        var directoryFile: File? = File(directoryPath)
        while (directoryFile != null) {
            if (directoryFile.absolutePath == mRootPath) {
                pathData.add(FilePathBean("根目录", directoryFile.absolutePath))
                break
            }
            pathData.add(FilePathBean(directoryFile.name, directoryFile.absolutePath))
            directoryFile = directoryFile.parentFile
        }
        return pathData.reversed()
    }

    /**
     * 获取文件夹内文件信息
     */
    private fun getDirectoryChildData(directory: File): List<FileManagerBean> {
        val fileManagerData = mutableListOf<FileManagerBean>()
        if (!directory.exists() || !directory.isDirectory) {
            return fileManagerData
        }
        val childFiles = directory.listFiles() ?: return fileManagerData

        for (childFile in childFiles) {
            when {
                childFile.isDirectory -> {
                    fileManagerData.add(
                        FileManagerBean(
                            childFile.absolutePath,
                            getFolderName(childFile.absolutePath),
                            isDirectory = true,
                        ),
                    )
                }

                isTargetFile(childFile.absolutePath) -> {
                    fileManagerData.add(
                        FileManagerBean(
                            childFile.absolutePath,
                            getFileName(childFile.absolutePath),
                            isDirectory = false,
                        ),
                    )
                }
            }
        }

        return fileManagerData
            .asSequence()
            .filterHiddenFile { it.fileName }
            .sortedWith(
                FileNameComparator(
                    getName = { it.fileName },
                    isDirectory = { it.isDirectory },
                ),
            )
    }

    /**
     * 是否为目标文件类型
     */
    private fun isTargetFile(filePath: String): Boolean =
        when (mTrackType) {
            TrackType.AUDIO -> isAudioFile(filePath)
            TrackType.DANMU -> isDanmuFile(filePath)
            TrackType.SUBTITLE -> isSubtitleFile(filePath)
            TrackType.VIDEO -> isVideoFile(filePath)
        }

    /**
     * 获取常用目录列表
     */
    private fun getCommonDirectoryList(): MutableList<FilePathBean> {
        val commonDirectoryList = mutableListOf<FilePathBean>()

        // 常用目录1
        val commonlyFolder1 = AppConfig.getCommonlyFolder1()
        if (commonlyFolder1?.isNotEmpty() == true) {
            commonDirectoryList.add(FilePathBean("常用目录1", commonlyFolder1))
        }

        // 常用目录2
        val commonlyFolder2 = AppConfig.getCommonlyFolder2()
        if (commonlyFolder2?.isNotEmpty() == true) {
            commonDirectoryList.add(FilePathBean("常用目录1", commonlyFolder2))
        }

        // 本次打开目录
        commonDirectoryList.add(FilePathBean("默认目录", getDefaultOpenDirectory()))

        // 上次打开目录
        val lastOpenFolderPath = AppConfig.getLastOpenFolder()
        if (AppConfig.isLastOpenFolderEnable() && lastOpenFolderPath?.isNotEmpty() == true) {
            commonDirectoryList.add(FilePathBean("上次使用", lastOpenFolderPath))
        }

        // 视频所在目录
        val videoFolder = PlayerInitializer.selectSourceDirectory
        if (videoFolder?.isNotEmpty() == true) {
            commonDirectoryList.add(FilePathBean("视频目录", videoFolder))
        }

        return commonDirectoryList
    }

    /**
     * 获取默认打开的目录
     * 优先：当前已加载文件所在目录
     * 否则：默认缓存目录
     */
    private fun getDefaultOpenDirectory(): String {
        val addedSourcePath =
            when (mTrackType) {
                TrackType.AUDIO -> mControlWrapper.getVideoSource().getAudioPath()
                TrackType.DANMU -> mControlWrapper.getVideoSource().getDanmu()?.danmuPath
                TrackType.SUBTITLE -> mControlWrapper.getVideoSource().getSubtitlePath()
                TrackType.VIDEO -> null
            }
        if (addedSourcePath.isNullOrEmpty()) {
            return PathHelper.getCachePath()
        }

        val parentDirectory = File(addedSourcePath).parentFile
        if (parentDirectory != null && parentDirectory.isValid()) {
            return parentDirectory.absolutePath
        }

        return PathHelper.getCachePath()
    }
}
