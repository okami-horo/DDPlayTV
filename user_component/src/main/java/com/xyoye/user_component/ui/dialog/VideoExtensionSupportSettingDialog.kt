package com.xyoye.user_component.ui.dialog

import android.app.Activity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.meida.VideoExtension
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.DialogVideoExtensionSupportSettingBinding

/**
 * Created by xyoye on 2024/2/4
 */

class VideoExtensionSupportSettingDialog(
    activity: Activity
) : BaseBottomDialog<DialogVideoExtensionSupportSettingBinding>(activity) {
    override fun getChildLayoutId() = R.layout.dialog_video_extension_support_setting

    override fun initView(binding: DialogVideoExtensionSupportSettingBinding) {
        setTitle("支持的视频扩展名")
        setPositiveText("保存")

        binding.etVideoExtension.setText(VideoExtension.supportText)
        binding.etVideoExtension.addTextChangedListener { binding.errorTv.isVisible = false }

        binding.tvResetExtension.setOnClickListener { resetExtension(binding) }

        setNegativeListener { dismiss() }

        setPositiveListener { updateExtension(binding) }
    }

    private fun resetExtension(binding: DialogVideoExtensionSupportSettingBinding) {
        try {
            VideoExtension.resetDefault()
            binding.etVideoExtension.setText(VideoExtension.supportText)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "VideoExtensionSupportSettingDialog",
                "resetExtension",
                "Failed to reset video extension settings",
            )
            showError(binding, "重置扩展名设置失败，请重试。")
        }
    }

    private fun updateExtension(binding: DialogVideoExtensionSupportSettingBinding) {
        try {
            val extensionText = binding.etVideoExtension.text.toString()
            if (extensionText.isEmpty()) {
                showError(binding, "请输入至少一个扩展名。")
                return
            }
            val updateSuccess = VideoExtension.update(extensionText)
            if (updateSuccess.not()) {
                showError(binding, "请输入至少一个扩展名。")
                return
            }
            dismiss()
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "VideoExtensionSupportSettingDialog",
                "updateExtension",
                "Failed to update video extension settings with text: ${binding.etVideoExtension.text}",
            )
            showError(binding, "更新扩展名设置失败，请重试。")
        }
    }

    private fun showError(
        binding: DialogVideoExtensionSupportSettingBinding,
        message: String
    ) {
        binding.errorTv.text = message
        binding.errorTv.isVisible = true
        binding.etVideoExtension.requestFocus()
    }
}
