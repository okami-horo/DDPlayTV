package com.xyoye.storage_component.ui.fragment.storage_file

import com.xyoye.common_component.storage.file.StorageFile

data class StorageBrowseState(
    val directory: StorageFile? = null,
    val directoryFiles: List<StorageFile> = emptyList(),
    val items: List<Any> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val loginRequired: Boolean = false,
    val hasDirectory: Boolean = false
)
