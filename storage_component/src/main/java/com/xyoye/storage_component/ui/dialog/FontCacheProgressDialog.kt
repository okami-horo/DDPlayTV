package com.xyoye.storage_component.ui.dialog

import android.app.Dialog
import android.content.Context
import androidx.databinding.DataBindingUtil
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogFontCacheProgressBinding

class FontCacheProgressDialog(
    context: Context
) : Dialog(context, com.xyoye.common_component.R.style.LoadingDialog) {
    private val binding: DialogFontCacheProgressBinding =
        DataBindingUtil.inflate(layoutInflater, R.layout.dialog_font_cache_progress, null, false)

    init {
        setContentView(binding.root)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        binding.progressPercentTv.text = context.getString(R.string.text_font_cache_progress_percent, 0)
        binding.progressTv.text = context.getString(R.string.text_font_cache_progress_default)
    }

    fun update(
        total: Int,
        cached: Int
    ) {
        val safeTotal = total.coerceAtLeast(1)
        val safeCached = cached.coerceIn(0, safeTotal)
        val percent = if (total <= 0) 0 else (safeCached * 100 / total)

        binding.progressPb.max = safeTotal
        binding.progressPb.progress = safeCached
        binding.progressTv.text =
            context.getString(R.string.text_font_cache_progress_format, safeCached, total)
        binding.progressPercentTv.text =
            context.getString(R.string.text_font_cache_progress_percent, percent)
    }
}
