package com.xyoye.storage_component.ui.dialog

import androidx.core.view.isVisible
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogBaiduPanStorageBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity

class BaiduPanStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalLibrary: MediaLibraryEntity?
) : StorageEditDialog<DialogBaiduPanStorageBinding>(activity) {
    private lateinit var binding: DialogBaiduPanStorageBinding
    private lateinit var editLibrary: MediaLibraryEntity

    override fun getChildLayoutId() = R.layout.dialog_baidu_pan_storage

    override fun initView(binding: DialogBaiduPanStorageBinding) {
        this.binding = binding

        val isEditMode = originalLibrary != null
        setTitle(if (isEditMode) "编辑百度网盘存储库" else "添加百度网盘存储库")

        editLibrary =
            originalLibrary ?: MediaLibraryEntity(
                id = 0,
                displayName = "",
                url = "",
                mediaType = MediaType.BAIDU_PAN_STORAGE,
            )
        binding.library = editLibrary
        PlayerTypeOverrideBinder.bind(binding.playerTypeOverrideLayout, editLibrary)

        binding.authActionTv.setOnClickListener { showLoginDialog() }

        setPositiveListener {
            if (editLibrary.displayName.isBlank()) {
                editLibrary.displayName = "百度网盘"
            }
            editLibrary.url = editLibrary.url.trim().removeSuffix("/")
            activity.addStorage(editLibrary)
        }

        setNegativeListener {
            activity.finish()
        }

        refreshAuthViews()
    }

    override fun onTestResult(result: Boolean) {
        // Baidu Pan storage does not support test action in dialog for now.
    }

    private fun showLoginDialog() {
        BaiduPanLoginDialog(
            activity = activity,
            onLoginSuccess = { token, uinfo ->
                val uk = uinfo.uk ?: 0L
                if (uk <= 0L) {
                    ToastCenter.showError("授权失败：uk 无效")
                    return@BaiduPanLoginDialog
                }

                val oldStorageKey = editLibrary.url.takeIf { it.isNotBlank() }?.let { BaiduPanAuthStore.storageKey(editLibrary) }

                val nowMs = System.currentTimeMillis()
                val url = "baidupan://uk/$uk"
                editLibrary.url = url

                if (editLibrary.displayName.isBlank()) {
                    editLibrary.displayName = uinfo.netdiskName?.takeIf { it.isNotBlank() } ?: "百度网盘"
                }

                val newStorageKey = BaiduPanAuthStore.storageKey(editLibrary)
                val expiresAtMs = nowMs + token.expiresIn * 1000L

                BaiduPanAuthStore.writeTokens(
                    storageKey = newStorageKey,
                    accessToken = token.accessToken,
                    expiresAtMs = expiresAtMs,
                    refreshToken = token.refreshToken,
                    scope = token.scope,
                    updatedAtMs = nowMs,
                )
                BaiduPanAuthStore.writeProfile(
                    storageKey = newStorageKey,
                    uk = uk,
                    netdiskName = uinfo.netdiskName,
                    avatarUrl = uinfo.avatarUrl,
                    updatedAtMs = nowMs,
                )

                if (!oldStorageKey.isNullOrBlank() && oldStorageKey != newStorageKey) {
                    BaiduPanAuthStore.clear(oldStorageKey)
                }

                ToastCenter.showOriginalToast("授权成功")
                refreshAuthViews()
            },
            onDismiss = { refreshAuthViews() },
        ).show()
    }

    private fun refreshAuthViews() {
        binding.urlValueTv.text = editLibrary.url.ifBlank { "未绑定账号" }

        val state =
            runCatching {
                BaiduPanAuthStore.read(BaiduPanAuthStore.storageKey(editLibrary))
            }.getOrNull()

        val isAuthorized = state?.isAuthorized() == true
        val uk = state?.uk?.takeIf { it > 0 }?.toString().orEmpty()

        binding.authStatusTv.text =
            if (isAuthorized) {
                "已授权" + (if (uk.isNotBlank()) "（uk=$uk）" else "")
            } else {
                "未授权"
            }
        binding.authActionTv.text = if (isAuthorized) "重新授权" else "扫码授权"

        binding.hintTv.isVisible = true
    }
}

