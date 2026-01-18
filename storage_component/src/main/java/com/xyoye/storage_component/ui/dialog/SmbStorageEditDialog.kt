package com.xyoye.storage_component.ui.dialog

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import androidx.core.view.isGone
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.hierynomus.smbj.SMBClient
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogSmbLoginBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity

/**
 * Created by xyoye on 2021/2/2.
 */

class SmbStorageEditDialog(
    private val activity: StoragePlusActivity,
    private var originalStorage: MediaLibraryEntity?
) : StorageEditDialog<DialogSmbLoginBinding>(activity) {
    private lateinit var binding: DialogSmbLoginBinding

    override fun getChildLayoutId() = R.layout.dialog_smb_login

    override fun initView(binding: DialogSmbLoginBinding) {
        this.binding = binding
        val isEditStorage = originalStorage != null

        setTitle(if (isEditStorage) "编辑SMB帐号" else "添加SMB帐号")

        val serverData =
            originalStorage ?: MediaLibraryEntity(
                0,
                "",
                "",
                MediaType.SMB_SERVER,
            )
        // SMB默认端口
        if (serverData.port == 0) {
            serverData.port = SMBClient.DEFAULT_PORT
        }
        setSmbV2(serverData.smbV2)
        setAnonymous(serverData.isAnonymous)
        binding.serverData = serverData
        val autoSaveHelper =
            StorageAutoSaveHelper(
                coroutineScope = activity.lifecycleScope,
                buildLibrary = { buildLibraryIfValid(serverData, showToast = false) },
                onSave = { saveStorage(it, showToast = false) },
            )
        registerAutoSaveHelper(autoSaveHelper)

        PlayerTypeOverrideBinder.bind(
            binding.playerTypeOverrideLayout,
            serverData,
            onChanged = { autoSaveHelper.requestSave() },
        )
        autoSaveHelper.markSaved(buildLibraryIfValid(serverData, showToast = false))

        binding.addressEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.portEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.displayNameEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.pathEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.accountEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.passwordEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })

        binding.serverTestConnectTv.setOnClickListener {
            val testLibrary = buildLibraryIfValid(serverData, showToast = true) ?: return@setOnClickListener
            activity.testStorage(testLibrary)
        }

        binding.anonymousTv.setOnClickListener {
            serverData.isAnonymous = true
            setAnonymous(true)
            autoSaveHelper.requestSave()
        }

        binding.accountTv.setOnClickListener {
            serverData.isAnonymous = false
            setAnonymous(false)
            autoSaveHelper.requestSave()
        }

        binding.smbV2Tv.setOnClickListener {
            serverData.smbV2 = true
            setSmbV2(true)
            autoSaveHelper.requestSave()
        }

        binding.smbV1Tv.setOnClickListener {
            serverData.smbV2 = false
            setSmbV2(false)
            autoSaveHelper.requestSave()
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
        val host = serverData.url.trim()
        if (host.isEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写IP地址")
            }
            return null
        }

        if (!serverData.isAnonymous) {
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
        }

        val port = serverData.port.takeIf { it > 0 } ?: SMBClient.DEFAULT_PORT
        val displayName = serverData.displayName.ifEmpty { "SMB媒体库" }
        return serverData.copy(
            displayName = displayName,
            url = host,
            port = port,
            describe = "smb://$host",
        )
    }

    private fun setAnonymous(isAnonymous: Boolean) {
        binding.anonymousTv.isSelected = isAnonymous
        binding.anonymousTv.setTextColorRes(
            if (isAnonymous) R.color.text_white else R.color.text_black,
        )

        binding.accountTv.isSelected = !isAnonymous
        binding.accountTv.setTextColorRes(
            if (!isAnonymous) R.color.text_white else R.color.text_black,
        )

        binding.accountEt.isGone = isAnonymous
        binding.passwordEt.isGone = isAnonymous
        binding.passwordFl.isGone = isAnonymous

        if (isAnonymous) {
            binding.accountEt.setText("")
            binding.passwordEt.setText("")
        }
    }

    private fun setSmbV2(isSmbV2: Boolean) {
        binding.smbV2Tv.isSelected = isSmbV2
        binding.smbV2Tv.setTextColorRes(
            if (isSmbV2) R.color.text_white else R.color.text_black,
        )

        binding.smbV1Tv.post {
            binding.smbV1Tv.isClickable = false
            binding.smbV1Tv.setTextColorRes(R.color.text_gray)
        }
        // 暂不支持SMB V1
//        binding.smbV1Tv.isSelected = !isSmbV2
//        binding.smbV1Tv.setTextColorRes(
//            if (!isSmbV2) R.color.text_white else R.color.text_black
//        )
    }
}
