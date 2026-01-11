package com.xyoye.storage_component.ui.fragment.storage_file

import com.xyoye.common_component.storage.PagedStorage

data class StoragePagingItem(
    val state: PagedStorage.State,
    val hasMore: Boolean,
    val isDataEmpty: Boolean
)
