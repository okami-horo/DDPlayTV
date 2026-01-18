package com.xyoye.storage_component.ui.dialog

import com.xyoye.data_component.entity.MediaLibraryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StorageAutoSaveHelper(
    private val coroutineScope: CoroutineScope,
    private val debounceMillis: Long = 400L,
    private val buildLibrary: () -> MediaLibraryEntity?,
    private val onSave: (MediaLibraryEntity) -> Job,
) {
    private var lastSavedConfig: MediaLibraryEntity? = null
    private var debounceJob: Job? = null
    private var lastSaveJob: Job? = null

    fun markSaved(library: MediaLibraryEntity?) {
        lastSavedConfig = library?.copy(id = 0)
    }

    fun requestSave() {
        debounceJob?.cancel()
        debounceJob =
            coroutineScope.launch {
                delay(debounceMillis)
                saveIfNeeded()
            }
    }

    fun flush(): Job? {
        debounceJob?.cancel()
        saveIfNeeded()
        return lastSaveJob?.takeIf { it.isActive }
    }

    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun saveIfNeeded() {
        val library = buildLibrary.invoke() ?: return
        val compareConfig = library.copy(id = 0)
        if (compareConfig == lastSavedConfig) {
            return
        }
        lastSavedConfig = compareConfig
        lastSaveJob = onSave.invoke(library)
    }
}
