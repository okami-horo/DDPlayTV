package com.xyoye.storage_component.ui.dialog

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import androidx.core.view.isGone
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogFtpLoginBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import java.nio.charset.Charset

/**
 * Created by xyoye on 2021/1/28.
 */

class FTPStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalStorage: MediaLibraryEntity?
) : StorageEditDialog<DialogFtpLoginBinding>(activity) {
    private lateinit var binding: DialogFtpLoginBinding

    override fun getChildLayoutId() = R.layout.dialog_ftp_login

    override fun initView(binding: DialogFtpLoginBinding) {
        this.binding = binding
        val isEditStorage = originalStorage != null

        setTitle(if (isEditStorage) "编辑FTP帐号" else "添加FTP帐号")

        val serverData =
            originalStorage ?: MediaLibraryEntity(
                0,
                "",
                "",
                MediaType.FTP_SERVER,
                null,
                null,
                false,
                21,
            )
        binding.serverData = serverData

        // 编辑模式下，选中匿名
        if (isEditStorage && originalStorage!!.isAnonymous) {
            setAnonymous(true)
        } else {
            setAnonymous(serverData.isAnonymous)
        }
        setActive(serverData.isActiveFTP)

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
        binding.encodingEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
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

        binding.activeTv.setOnClickListener {
            serverData.isActiveFTP = true
            setActive(true)
            autoSaveHelper.requestSave()
        }

        binding.passiveTv.setOnClickListener {
            serverData.isActiveFTP = false
            setActive(false)
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
        val address = serverData.ftpAddress.trim()
        if (address.isEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写FTP地址")
            }
            return null
        }

        val encoding = serverData.ftpEncoding.trim()
        if (encoding.isEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写编码格式")
            }
            return null
        }

        if (!Charset.isSupported(encoding)) {
            if (showToast) {
                ToastCenter.showWarning("不支持的编码格式")
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

        val port = serverData.port.takeIf { it > 0 } ?: 21
        val displayName = serverData.displayName.ifEmpty { "FTP媒体库" }
        val url =
            if (address.contains("//")) {
                "$address:$port"
            } else {
                "ftp://$address:$port"
            }

        return serverData.copy(
            displayName = displayName,
            url = url,
            port = port,
            ftpAddress = address,
            ftpEncoding = encoding,
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

    private fun setActive(isActive: Boolean) {
        binding.activeTv.isSelected = isActive
        binding.activeTv.setTextColorRes(
            if (isActive) R.color.text_white else R.color.text_black,
        )

        binding.passiveTv.isSelected = !isActive
        binding.passiveTv.setTextColorRes(
            if (!isActive) R.color.text_white else R.color.text_black,
        )
    }
}
