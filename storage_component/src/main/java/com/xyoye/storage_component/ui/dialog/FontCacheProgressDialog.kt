package com.xyoye.storage_component.ui.dialog

import android.app.Dialog
import android.content.Context
import androidx.databinding.DataBindingUtil
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogFontCacheProgressBinding

class FontCacheProgressDialog(context: Context) :
    Dialog(context, com.xyoye.common_component.R.style.LoadingDialog) {

    private val binding: DialogFontCacheProgressBinding =
        DataBindingUtil.inflate(layoutInflater, R.layout.dialog_font_cache_progress, null, false)

    init {
        setContentView(binding.root)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    fun update(total: Int, cached: Int) {
        binding.progressPb.max = total
        binding.progressPb.progress = cached
        binding.progressTv.text =
            context.getString(R.string.text_font_cache_progress_format, cached, total)
    }
}
