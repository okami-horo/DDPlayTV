package com.xyoye.storage_component.ui.weight

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogFileBrowserSearchBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity

class StorageFileMenus(
    private val activity: StorageFileActivity,
    private val onSearch: (String) -> Unit,
    private val onSort: () -> Unit
) {
    fun search(
        query: String,
        caller: View
    ) {
        object : BaseBottomDialog<DialogFileBrowserSearchBinding>(activity) {
            override fun getChildLayoutId() = R.layout.dialog_file_browser_search

            override fun onStart() {
                super.onStart()
                window?.apply {
                    WindowCompat.getInsetsController(this, decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }

            override fun initView(binding: DialogFileBrowserSearchBinding) {
                val remote = activity.storage.supportSearch()
                setTitle(if (remote) "搜索当前媒体库" else "搜索当前目录")
                binding.searchScope.text = if (remote) "使用当前来源提供的搜索范围" else "筛选当前目录已加载的文件与文件夹"
                binding.searchInput.setText(query)
                setPositiveText("搜索")
                setPositiveListener {
                    onSearch(
                        binding.searchInput.text
                            .toString()
                            .trim()
                    )
                    dismiss()
                }
                setNegativeText("取消")
                setNegativeListener { dismiss() }
                addNeutralButton("清除搜索") {
                    onSearch("")
                    dismiss()
                }
                binding.searchInput.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEARCH) {
                        onSearch(
                            binding.searchInput.text
                                .toString()
                                .trim()
                        )
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
                setOnDismissListener { caller.requestFocus() }
            }
        }.show()
    }

    fun sort(caller: View) {
        val choices =
            listOf(
                SheetActionBean(StorageSort.NAME, "按名称排序", describe = if (StorageSortOption.getSort() == StorageSort.NAME) "已选" else null),
                SheetActionBean(StorageSort.SIZE, "按大小排序", describe = if (StorageSortOption.getSort() == StorageSort.SIZE) "已选" else null),
                SheetActionBean("order", if (StorageSortOption.isAsc()) "升序 · 切换为降序" else "降序 · 切换为升序"),
                SheetActionBean("directories", if (StorageSortOption.isDirectoryFirst()) "文件夹优先 · 已开启" else "文件夹优先 · 已关闭")
            )
        BottomActionDialog(activity, choices, "文件排序") {
            when (val action = it.actionId) {
                is StorageSort -> StorageSortOption.setSort(action)
                "order" -> StorageSortOption.changeAsc()
                "directories" -> StorageSortOption.changeDirectoryFirst()
            }
            onSort()
            true
        }.apply { setOnDismissListener { caller.requestFocus() } }.show()
    }

    fun scan() {
        CommonDialog
            .Builder(activity)
            .apply {
                content = "扫描当前目录及子目录的作品资料？文件不会被改名或删除。本地来源仅读取已有视频索引。"
                addPositive("开始扫描") { dialog ->
                    dialog.dismiss()
                    MetadataCoordinator.startScan(activity.storage.library.id, activity.directory?.storagePath().orEmpty())
                    ARouter.getInstance().build(RouteTable.User.MetadataSettings).navigation()
                }
                addNegative()
            }.build()
            .show()
    }

    fun sortLabel(): String =
        (if (StorageSortOption.getSort() == StorageSort.NAME) "名称" else "大小") +
            (if (StorageSortOption.isAsc()) "升序" else "降序")
}
