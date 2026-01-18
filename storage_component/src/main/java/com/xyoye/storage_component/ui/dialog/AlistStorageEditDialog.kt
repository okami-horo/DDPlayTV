package com.xyoye.storage_component.ui.dialog

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogAlistLoginBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity

/**
 * Created by xyoye on 2021/1/26.
 */

class AlistStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val library: MediaLibraryEntity?
) : StorageEditDialog<DialogAlistLoginBinding>(activity) {
    private lateinit var binding: DialogAlistLoginBinding

    override fun getChildLayoutId() = R.layout.dialog_alist_login

    override fun initView(binding: DialogAlistLoginBinding) {
        this.binding = binding
        val isEditMode = library != null

        setTitle(if (isEditMode) "编辑Alist帐号" else "添加Alist帐号")

        val editLibrary =
            library ?: MediaLibraryEntity(
                0,
                "",
                "",
                MediaType.ALSIT_STORAGE,
            )
        binding.library = editLibrary
        val autoSaveHelper =
            StorageAutoSaveHelper(
                coroutineScope = activity.lifecycleScope,
                buildLibrary = { buildLibraryIfValid(editLibrary, showToast = false) },
                onSave = { saveStorage(it, showToast = false) },
            )
        registerAutoSaveHelper(autoSaveHelper)

        PlayerTypeOverrideBinder.bind(
            binding.playerTypeOverrideLayout,
            editLibrary,
            onChanged = { autoSaveHelper.requestSave() },
        )
        autoSaveHelper.markSaved(buildLibraryIfValid(editLibrary, showToast = false))

        binding.addressEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.displayNameEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.accountEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.passwordEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })

        binding.serverTestConnectTv.setOnClickListener {
            val testLibrary = buildLibraryIfValid(editLibrary, showToast = true) ?: return@setOnClickListener
            activity.testStorage(testLibrary)
        }

        binding.passwordToggleIv.setOnClickListener {
            if (binding.passwordToggleIv.isSelected) {
                binding.passwordToggleIv.isSelected = false
                binding.passwordEt.transformationMethod =
                    PasswordTransformationMethod.getInstance()
            } else {
                binding.passwordToggleIv.isSelected = true
                binding.passwordEt.transformationMethod =
                    HideReturnsTransformationMethod.getInstance()
            }
        }
    }

    override fun onTestResult(result: Boolean) {
        if (result) {
            binding.serverStatusTv.text = "连接成功"
            binding.serverStatusTv.setTextColorRes(R.color.text_blue)
        } else {
            binding.serverStatusTv.text = "连接失败"
            binding.serverStatusTv.setTextColorRes(R.color.text_red)
        }
    }

    private fun buildLibraryIfValid(
        serverData: MediaLibraryEntity,
        showToast: Boolean,
    ): MediaLibraryEntity? {
        val url = serverData.url.trim()
        if (url.isEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写服务器地址")
            }
            return null
        }
        val normalizedUrl = if (url.endsWith("/")) url else "$url/"
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            if (showToast) {
                ToastCenter.showWarning("请填写服务器协议：http或https")
            }
            return null
        }

        if (serverData.account.isNullOrEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写帐号")
            }
            return null
        }
        if (serverData.password.isNullOrEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写密码")
            }
            return null
        }

        val displayName = serverData.displayName.ifEmpty { "Alist媒体库" }
        return serverData.copy(
            displayName = displayName,
            url = normalizedUrl,
            describe = normalizedUrl,
        )
    }
}
