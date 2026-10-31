package com.xyoye.storage_component.ui.dialog

import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import com.xyoye.common_component.utils.dp2px
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogFontCacheProgressBinding

class FontCacheProgressDialog(
    context: Context
) : Dialog(context, com.xyoye.core_ui_component.R.style.CinemaDialog) {
    private val binding: DialogFontCacheProgressBinding =
        DataBindingUtil.inflate(layoutInflater, R.layout.dialog_font_cache_progress, null, false)

    init {
        setContentView(binding.root)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        binding.progressPercentTv.text = context.getString(R.string.text_font_cache_progress_percent, 0)
        binding.progressTv.text = context.getString(R.string.text_font_cache_progress_default)
    }

    override fun onStart() {
        super.onStart()
        window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        window?.setLayout(
            minOf(dp2px(440), context.resources.displayMetrics.widthPixels - dp2px(48)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    fun update(
        total: Int,
        cached: Int
    ) {
        val safeTotal = total.coerceAtLeast(0)
        val safeCached = cached.coerceIn(0, safeTotal)
        val percent = if (safeTotal == 0) 0 else (safeCached.toLong() * 100 / safeTotal).toInt()

        binding.progressPb.max = safeTotal.coerceAtLeast(1)
        binding.progressPb.progress = safeCached
        binding.progressTv.text =
            context.getString(R.string.text_font_cache_progress_format, safeCached, safeTotal)
        binding.progressStateTv.text = if (safeTotal == 0) "正在准备…" else "正在缓存…"
        binding.progressPercentTv.text =
            context.getString(R.string.text_font_cache_progress_percent, percent)
    }
}
