package com.xyoye.storage_component.ui.dialog

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
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

        setPositiveVisible(false)
        setNegativeText("返回")

        setOnDismissListener {
            doBeforeDismiss()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focused = currentFocus
        if (focused is EditText && !focused.isInTouchMode && event.action == KeyEvent.ACTION_DOWN && event.hasNoModifiers()) {
            val direction =
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    else -> null
                }
            // Single-line editors consume vertical arrows before the window can move focus.
            if (direction != null && focused.focusSearch(direction)?.requestFocus(direction) == true) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    protected fun registerAutoSaveHelper(helper: StorageAutoSaveHelper) {
        autoSaveHelper = helper
    }

    protected fun saveStorage(
        library: MediaLibraryEntity,
        showToast: Boolean = true
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
