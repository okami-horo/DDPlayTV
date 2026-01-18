package com.xyoye.storage_component.ui.dialog

import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.config.PlayerActions
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogBaiduPanStorageBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BaiduPanStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalLibrary: MediaLibraryEntity?
) : StorageEditDialog<DialogBaiduPanStorageBinding>(activity) {
    private lateinit var binding: DialogBaiduPanStorageBinding
    private lateinit var editLibrary: MediaLibraryEntity
    private lateinit var autoSaveHelper: StorageAutoSaveHelper
    private var allowAutoSave: Boolean = true

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
        autoSaveHelper =
            StorageAutoSaveHelper(
                coroutineScope = activity.lifecycleScope,
                buildLibrary = { buildLibraryIfValid(showToast = false) },
                onSave = { saveStorage(it) },
            )
        registerAutoSaveHelper(autoSaveHelper)

        PlayerTypeOverrideBinder.bind(
            binding.playerTypeOverrideLayout,
            editLibrary,
            onChanged = { autoSaveHelper.requestSave() },
        )
        binding.displayNameEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })

        autoSaveHelper.markSaved(buildLibraryIfValid(showToast = false))

        binding.authActionTv.setOnClickListener { showLoginDialog() }

        binding.disconnectTv.isVisible = (activity.editData?.id ?: editLibrary.id) > 0
        binding.disconnectTv.setOnClickListener { showDisconnectDialog() }

        refreshAuthViews()
    }

    override fun onTestResult(result: Boolean) {
        // Baidu Pan storage does not support test action in dialog for now.
    }

    private fun showDisconnectDialog() {
        val libraryId = activity.editData?.id ?: editLibrary.id
        if (libraryId <= 0) return

        val actions =
            listOf(
                SheetActionBean(
                    actionId = DisconnectAction.CLEAR_AUTH,
                    actionName = "仅清除授权",
                    describe = "保留媒体库，可稍后重新扫码授权"
                ),
                SheetActionBean(
                    actionId = DisconnectAction.CLEAR_AUTH_AND_DELETE_LIBRARY,
                    actionName = "清除授权并删除媒体库",
                    describe = "从列表移除该账号（不会删除网盘文件）"
                )
            )

        BottomActionDialog(activity, actions, "断开连接") {
            val action = it.actionId as? DisconnectAction ?: return@BottomActionDialog false
            showDisconnectConfirmDialog(action)
            true
        }.show()
    }

    private fun showDisconnectConfirmDialog(action: DisconnectAction) {
        val libraryId = activity.editData?.id ?: editLibrary.id
        if (libraryId <= 0) return

        val deleteLibrary = action == DisconnectAction.CLEAR_AUTH_AND_DELETE_LIBRARY
        val content =
            if (deleteLibrary) {
                "确认清除授权并删除该媒体库？\n\n将清除：百度网盘授权信息（access_token/refresh_token、账号信息缓存）。\n\n同时将退出播放器。"
            } else {
                "确认断开连接并清除授权？\n\n将清除：百度网盘授权信息（access_token/refresh_token、账号信息缓存）。\n\n清除后需要重新扫码授权才能浏览/播放，并将退出播放器。"
            }
        val positiveText = if (deleteLibrary) "确认删除" else "确认清除"

        CommonDialog
            .Builder(activity)
            .apply {
                tips = "提示"
                this.content = content
                addPositive(positiveText = positiveText) { dialog ->
                    dialog.dismiss()
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val dao = DatabaseManager.instance.getMediaLibraryDao()
                            val storedLibrary = dao.getById(libraryId)
                            val storedKey = storedLibrary?.let { BaiduPanAuthStore.storageKey(it) }
                            val currentKey = BaiduPanAuthStore.storageKey(editLibrary)

                            if (!storedKey.isNullOrBlank()) {
                                BaiduPanAuthStore.clear(storedKey)
                            }
                            if (currentKey != storedKey) {
                                BaiduPanAuthStore.clear(currentKey)
                            }

                            if (deleteLibrary) {
                                storedLibrary?.let { dao.delete(it.url, it.mediaType) }
                            }
                        }

                        PlayerActions.sendExitPlayer(activity, libraryId)

                        if (deleteLibrary) {
                            allowAutoSave = false
                            ToastCenter.showOriginalToast("已清除授权并删除媒体库")
                            dismiss()
                            activity.finish()
                        } else {
                            ToastCenter.showOriginalToast("已清除授权")
                            refreshAuthViews()
                        }
                    }
                }
                addNegative()
            }.build()
            .show()
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
                val saveJob = autoSaveHelper.flush()
                refreshAuthViews()
                if (saveJob != null) {
                    activity.lifecycleScope.launch {
                        saveJob.join()
                        refreshAuthViews()
                    }
                }
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

        binding.disconnectTv.isVisible = (activity.editData?.id ?: editLibrary.id) > 0
    }

    private enum class DisconnectAction {
        CLEAR_AUTH,
        CLEAR_AUTH_AND_DELETE_LIBRARY
    }

    private fun buildLibraryIfValid(showToast: Boolean): MediaLibraryEntity? {
        if (!allowAutoSave) {
            return null
        }

        val url = editLibrary.url.trim().removeSuffix("/")
        val isValid = Regex("^baidupan://uk/\\d+$").matches(url)
        if (!isValid) {
            if (showToast) {
                ToastCenter.showWarning("保存失败，请先扫码授权")
            }
            return null
        }

        val displayName = editLibrary.displayName.trim().ifEmpty { "百度网盘" }
        return editLibrary.copy(
            displayName = displayName,
            url = url,
        )
    }
}
