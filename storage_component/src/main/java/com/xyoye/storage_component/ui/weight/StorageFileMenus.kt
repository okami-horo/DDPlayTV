package com.xyoye.storage_component.ui.weight

import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SearchView.OnQueryTextListener
import androidx.appcompat.widget.SearchView.SearchAutoComplete
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity

/**
 * Created by xyoye on 2023/3/31.
 */

class StorageFileMenus private constructor(
    private val activity: StorageFileActivity,
    menu: Menu
) {
    companion object {
        fun inflater(
            activity: StorageFileActivity,
            menu: Menu
        ): StorageFileMenus {
            activity.menuInflater.inflate(R.menu.menu_storage_file, menu)
            return StorageFileMenus(activity, menu)
        }
    }

    private val searchItem = menu.findItem(R.id.item_search)
    private val sortNameItem = menu.findItem(R.id.action_sort_by_name)
    private val sortSizeItem = menu.findItem(R.id.action_sort_by_size)
    private val sortOrderAsc = menu.findItem(R.id.action_sort_order_asc)
    private val sortDirectoryFirst = menu.findItem(R.id.action_sort_directory_first)

    private var mSearchView = searchItem.actionView as SearchView
    private var onTextChanged: ((String) -> Unit)? = null
    private var onSortChanged: (() -> Unit)? = null

    init {
        menu.findItem(R.id.action_scan_metadata).isVisible =
            com.xyoye.common_component.storage.catalog.CatalogSourceReader
                .supports(activity.storage.library.mediaType)
        initSearchView()
        updateSortItem()
    }

    private fun initSearchView() {
        mSearchView.apply {
            isIconified = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            queryHint = getSearchHintText()
            findViewById<SearchAutoComplete>(R.id.search_src_text)?.textSize = 16f
        }

        mSearchView.setOnQueryTextListener(
            object : OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    mSearchView.clearFocus()
                    return false
                }

                override fun onQueryTextChange(keyword: String): Boolean {
                    onTextChanged?.invoke(keyword)
                    return false
                }
            },
        )
    }

    private fun updateSortItem() {
        when (StorageSortOption.getSort()) {
            StorageSort.NAME -> sortNameItem
            StorageSort.SIZE -> sortSizeItem
        }.isChecked = true

        sortOrderAsc.isChecked = StorageSortOption.isAsc()
        sortDirectoryFirst.isChecked = StorageSortOption.isDirectoryFirst()
    }

    private fun getSearchHintText(): String {
        if (activity.storage.supportSearch()) {
            return "搜索当前媒体库"
        }
        return "搜索当前目录"
    }

    fun onOptionsItemSelected(item: MenuItem) {
        if (item.itemId == R.id.action_scan_metadata) {
            com.xyoye.common_component.weight.dialog.CommonDialog
                .Builder(activity)
                .apply {
                    content = "扫描当前目录及子目录的作品资料？文件不会被改名或删除。本地来源仅读取已有视频索引。"
                    positiveText = "开始扫描"
                    addPositive { dialog ->
                        dialog.dismiss()
                        com.xyoye.common_component.metadata.MetadataCoordinator.startScan(
                            activity.storage.library.id,
                            activity.storage.directory
                                ?.storagePath()
                                .orEmpty()
                        )
                        com.alibaba.android.arouter.launcher.ARouter
                            .getInstance()
                            .build(
                                com.xyoye.common_component.config.RouteTable.User.MetadataSettings
                            ).navigation()
                    }
                    addNegative()
                }.build()
                .show()
            return
        }
        val changed =
            when (item.itemId) {
                R.id.action_sort_by_name -> StorageSortOption.setSort(StorageSort.NAME)
                R.id.action_sort_by_size -> StorageSortOption.setSort(StorageSort.SIZE)
                R.id.action_sort_order_asc -> StorageSortOption.changeAsc()
                R.id.action_sort_directory_first -> StorageSortOption.changeDirectoryFirst()
                else -> false
            }
        if (changed) {
            updateSortItem()
            onSortChanged?.invoke()
        }
    }

    fun handleBackPressed(): Boolean {
        if (mSearchView.isIconified) {
            return false
        }
        mSearchView.onActionViewCollapsed()
        return true
    }

    fun onSearchTextChanged(block: (String) -> Unit) {
        onTextChanged = block
    }

    fun onSortTypeChanged(block: () -> Unit) {
        onSortChanged = block
    }
}
