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
import com.xyoye.storage_component.databinding.DialogWebDavLoginBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity

/**
 * Created by xyoye on 2021/1/26.
 */

class WebDavStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalStorage: MediaLibraryEntity?
) : StorageEditDialog<DialogWebDavLoginBinding>(activity) {
    private lateinit var binding: DialogWebDavLoginBinding

    override fun getChildLayoutId() = R.layout.dialog_web_dav_login

    override fun initView(binding: DialogWebDavLoginBinding) {
        this.binding = binding
        val isEditStorage = originalStorage != null

        setTitle(if (isEditStorage) "编辑WebDav帐号" else "添加WebDav帐号")

        val serverData =
            originalStorage ?: MediaLibraryEntity(
                0,
                "",
                "",
                MediaType.WEBDAV_SERVER,
            )
        setAnonymous(serverData.isAnonymous)
        setParseMode(serverData.webDavStrict)
        setTlsMode(serverData.webDavAllowInsecureTls)
        binding.serverData = serverData
        val autoSaveHelper =
            StorageAutoSaveHelper(
                coroutineScope = activity.lifecycleScope,
                buildLibrary = { buildLibraryIfValid(serverData, showToast = false) },
                onSave = { saveStorage(it, showToast = false) },
            )
        registerAutoSaveHelper(autoSaveHelper)

        autoSaveHelper.markSaved(buildLibraryIfValid(serverData, showToast = false))

        binding.addressEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.displayNameEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.accountEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.passwordEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })

        binding.serverTestConnectTv.setOnClickListener {
            val testLibrary = buildLibraryIfValid(serverData, showToast = true) ?: return@setOnClickListener
            activity.testStorage(testLibrary)
        }

        binding.strictParseTv.setOnClickListener {
            serverData.webDavStrict = true
            setParseMode(true)
            autoSaveHelper.requestSave()
        }

        binding.normalParseTv.setOnClickListener {
            serverData.webDavStrict = false
            setParseMode(false)
            autoSaveHelper.requestSave()
        }

        binding.strictTlsTv.setOnClickListener {
            serverData.webDavAllowInsecureTls = false
            setTlsMode(false)
            autoSaveHelper.requestSave()
        }

        binding.insecureTlsTv.setOnClickListener {
            serverData.webDavAllowInsecureTls = true
            setTlsMode(true)
            ToastCenter.showWarning("已启用不安全TLS：将忽略证书/主机名校验，存在中间人攻击风险")
            autoSaveHelper.requestSave()
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
        showToast: Boolean
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

        val displayName = serverData.displayName.ifEmpty { "WebDav媒体库" }
        return serverData.copy(
            displayName = displayName,
            url = normalizedUrl,
            describe = normalizedUrl,
        )
    }

    private fun setAnonymous(isAnonymous: Boolean) {
        binding.anonymousTv.isSelected = isAnonymous
        binding.anonymousTv.setTextColorRes(
            if (isAnonymous) R.color.cinema_text else R.color.cinema_muted,
        )

        binding.accountTv.isSelected = !isAnonymous
        binding.accountTv.setTextColorRes(
            if (!isAnonymous) R.color.cinema_text else R.color.cinema_muted,
        )

        binding.accountField.isGone = isAnonymous
        binding.passwordEt.isGone = isAnonymous
        binding.passwordFl.isGone = isAnonymous

        if (isAnonymous) {
            binding.accountEt.setText("")
            binding.passwordEt.setText("")
        }
    }

    private fun setParseMode(isStrict: Boolean) {
        binding.strictParseTv.isSelected = isStrict
        binding.strictParseTv.setTextColorRes(
            if (isStrict) R.color.cinema_text else R.color.cinema_muted,
        )

        binding.normalParseTv.isSelected = isStrict.not()
        binding.normalParseTv.setTextColorRes(
            if (isStrict.not()) R.color.cinema_text else R.color.cinema_muted,
        )
    }

    private fun setTlsMode(allowInsecure: Boolean) {
        val strict = !allowInsecure
        binding.strictTlsTv.isSelected = strict
        binding.strictTlsTv.setTextColorRes(
            if (strict) R.color.cinema_text else R.color.cinema_muted,
        )

        binding.insecureTlsTv.isSelected = allowInsecure
        binding.insecureTlsTv.setTextColorRes(
            if (allowInsecure) R.color.cinema_text else R.color.cinema_muted,
        )
    }
}
