package com.xyoye.storage_component.ui.dialog

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2023/4/24
 */

abstract class StorageEditDialog<T : ViewDataBinding>(
    private val activity: StoragePlusActivity
) : BaseBottomDialog<T>(activity) {
    private var autoSaveHelper: StorageAutoSaveHelper? = null
    private var lastSaveJob: Job? = null

    abstract fun onTestResult(result: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setBottomActionVisible(false)

        setOnDismissListener {
            doBeforeDismiss()
        }
    }

    protected fun registerAutoSaveHelper(helper: StorageAutoSaveHelper) {
        autoSaveHelper = helper
    }

    protected fun saveStorage(
        library: MediaLibraryEntity,
        showToast: Boolean = true,
    ): Job {
        val job = activity.addStorage(library, showToast)
        lastSaveJob = job
        return job
    }

    open fun doBeforeDismiss() {
        if (activity.isFinishing || activity.isDestroyed) {
            autoSaveHelper?.cancel()
            return
        }
        val pendingSaveJob =
            autoSaveHelper?.flush()
                ?: lastSaveJob?.takeIf { it.isActive }
        if (pendingSaveJob != null) {
            activity.lifecycleScope.launch {
                pendingSaveJob.join()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finish()
                }
            }
            return
        }
        activity.finish()
    }
}
