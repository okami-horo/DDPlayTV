package com.xyoye.local_component.ui.dialog

import android.app.Activity
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.DialogDanmuDowanloadBinding

/**
 * Created by xyoye on 2020/11/25.
 */

class DanmuDownloadDialog(
    activity: Activity,
    private val download: (withRelated: Boolean) -> Unit
) : BaseBottomDialog<DialogDanmuDowanloadBinding>(activity) {
    override fun getChildLayoutId() = R.layout.dialog_danmu_dowanload

    override fun initView(binding: DialogDanmuDowanloadBinding) {
        setTitle("下载弹幕")
        binding.downloadPathTv.text = PathHelper.getDanmuDirectory().absolutePath
        rootViewBinding.positiveBt.nextFocusUpId = binding.rbAllSource.id
        rootViewBinding.negativeBt.nextFocusUpId = binding.rbAllSource.id
        setNegativeListener { dismiss() }
        setPositiveListener { download(binding.rbAllSource.isChecked) }
    }
}
