package com.xyoye.storage_component.ui.dialog

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.config.PlayerActions
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.cloud115.auth.Cloud115AuthStore
import com.xyoye.common_component.storage.cloud115.auth.Cloud115TokenParser
import com.xyoye.common_component.storage.cloud115.auth.Cloud115TokenValidator
import com.xyoye.common_component.storage.cloud115.net.Cloud115Headers
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialStore
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.common_component.weight.dialog.CommonEditDialog
import com.xyoye.data_component.bean.EditBean
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogCloud115StorageBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Cloud115StorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalLibrary: MediaLibraryEntity?
) : StorageEditDialog<DialogCloud115StorageBinding>(activity) {
    private lateinit var binding: DialogCloud115StorageBinding
    private lateinit var editLibrary: MediaLibraryEntity
    private lateinit var autoSaveHelper: StorageAutoSaveHelper
    private var allowAutoSave: Boolean = true

    override fun getChildLayoutId() = R.layout.dialog_cloud115_storage

    override fun initView(binding: DialogCloud115StorageBinding) {
        this.binding = binding

        val isEditMode = originalLibrary != null
        LogFacade.d(
            LogModule.STORAGE,
            LOG_TAG,
            "init view",
            mapOf(
                "isEditMode" to isEditMode.toString(),
                "libraryId" to (originalLibrary?.id ?: 0).toString(),
            ),
        )
        setTitle(if (isEditMode) "编辑 115 Cloud 存储库" else "添加 115 Cloud 存储库")

        editLibrary =
            originalLibrary ?: MediaLibraryEntity(
                id = 0,
                displayName = "",
                url = "",
                mediaType = MediaType.CLOUD_115_STORAGE,
            )

        binding.library = editLibrary
        autoSaveHelper =
            StorageAutoSaveHelper(
                coroutineScope = activity.lifecycleScope,
                buildLibrary = { buildLibraryIfValid(showToast = false) },
                onSave = { saveStorage(it) },
            )
        registerAutoSaveHelper(autoSaveHelper)

        binding.displayNameEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })

        autoSaveHelper.markSaved(buildLibraryIfValid(showToast = false))

        binding.authActionTv.setOnClickListener { showAuthMethodDialog() }

        binding.disconnectTv.isVisible = (activity.editData?.id ?: editLibrary.id) > 0
        binding.disconnectTv.setOnClickListener { showDisconnectDialog() }

        refreshAuthViews()
    }

    override fun onTestResult(result: Boolean) {
        // Cloud115 storage does not support test action in dialog for now.
    }

    private fun showAuthMethodDialog() {
        val actions =
            listOf(
                SheetActionBean(
                    actionId = AuthMethod.QRCODE,
                    actionName = "扫码授权",
                    describe = "推荐：使用 115 App 扫码确认",
                ),
                SheetActionBean(
                    actionId = AuthMethod.TOKEN,
                    actionName = "手动输入 token",
                    describe = "粘贴 Cookie（UID/CID/SEID/KID）",
                ),
            )

        BottomActionDialog(activity, actions, "授权方式") {
            val method = it.actionId as? AuthMethod ?: return@BottomActionDialog false
            when (method) {
                AuthMethod.QRCODE -> showLoginDialog()
                AuthMethod.TOKEN -> showTokenLoginDialog()
            }
            true
        }.show()
    }

    private fun showLoginDialog() {
        val initialLoginApp =
            runCatching {
                val url = editLibrary.url.trim().removeSuffix("/")
                val isValid = Regex("^115cloud://uid/\\d+$").matches(url)
                if (!isValid) {
                    return@runCatching DEFAULT_LOGIN_APP
                }

                val storageKey = Cloud115AuthStore.storageKey(editLibrary)
                Cloud115AuthStore
                    .read(storageKey)
                    .loginApp
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_LOGIN_APP
            }.getOrDefault(DEFAULT_LOGIN_APP)

        val loginStorageKey = Cloud115AuthStore.storageKey(editLibrary)

        Cloud115LoginDialog(
            activity = activity,
            storageKey = loginStorageKey,
            initialLoginApp = initialLoginApp,
            onLoginSuccess = { result ->
                handleAuthSuccess(
                    userId = result.userId,
                    cookieHeader = result.cookieHeader,
                    loginApp = result.loginApp,
                    userName = result.userName,
                    avatarUrl = result.avatarUrl,
                    authMethod = "qrcode",
                )
            },
            onDismiss = { refreshAuthViews() },
        ).show()
    }

    private fun showTokenLoginDialog() {
        val host = activity as? AppCompatActivity
        if (host == null) {
            ToastCenter.showWarning("当前页面不支持手动输入")
            return
        }

        CommonEditDialog(
            activity = host,
            editBean =
                EditBean(
                    title = "手动输入 token（Cookie）",
                    emptyWarningMsg = "请粘贴 token（Cookie）",
                    hint = "UID=...; CID=...; SEID=...; KID=...",
                    defaultText = "",
                    inputTips = "将仅用于本机授权校验，请勿分享/截图。支持 Cookie: 前缀与大小写/空格差异。",
                    canInputEmpty = false,
                ),
            inputOnlyDigit = false,
            checkBlock = { input ->
                val parsed = Cloud115TokenParser.parse(input).exceptionOrNull()
                if (parsed != null) {
                    ToastCenter.showWarning(parsed.message ?: "token 格式不正确")
                    return@CommonEditDialog false
                }
                true
            },
            callback = { input ->
                activity.lifecycleScope.launch {
                    activity.showLoading("正在校验 token…")
                    try {
                        val parsed =
                            Cloud115TokenParser.parse(input).getOrElse { t ->
                                ToastCenter.showWarning(t.message ?: "token 格式不正确")
                                return@launch
                            }

                        val validateResult = Cloud115TokenValidator.validate(parsed.cookieHeader)
                        validateResult.exceptionOrNull()?.let { t ->
                            val safeCookie = Cloud115Headers.redactCookie(parsed.cookieHeader)
                            LogFacade.w(
                                LogModule.STORAGE,
                                LOG_TAG,
                                "token validate failed",
                                mapOf(
                                    "isEditMode" to (originalLibrary != null).toString(),
                                    "libraryId" to (activity.editData?.id ?: editLibrary.id).toString(),
                                    "userId" to parsed.userId,
                                    "cookie" to safeCookie,
                                    "exception" to t::class.java.simpleName,
                                ),
                                t,
                            )
                            ToastCenter.showError(t.message?.takeIf { it.isNotBlank() } ?: "token 校验失败")
                            return@launch
                        }

                        handleAuthSuccess(
                            userId = parsed.userId,
                            cookieHeader = parsed.cookieHeader,
                            loginApp = null,
                            userName = null,
                            avatarUrl = null,
                            authMethod = "token",
                        )
                    } finally {
                        activity.hideLoading()
                    }
                }
            },
        ).show()
    }

    private fun showDisconnectDialog() {
        val libraryId = activity.editData?.id ?: editLibrary.id
        if (libraryId <= 0) return

        val actions =
            listOf(
                SheetActionBean(
                    actionId = DisconnectAction.CLEAR_AUTH,
                    actionName = "仅清除授权",
                    describe = "保留媒体库，可稍后重新授权",
                ),
                SheetActionBean(
                    actionId = DisconnectAction.CLEAR_AUTH_AND_DELETE_LIBRARY,
                    actionName = "清除授权并删除媒体库",
                    describe = "从列表移除该账号（不会删除云盘文件）",
                ),
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
                "确认清除授权并删除该媒体库？\n\n将清除：115 Cloud 授权信息（Cookie、账号信息缓存）。\n\n同时将退出播放器。"
            } else {
                "确认断开连接并清除授权？\n\n将清除：115 Cloud 授权信息（Cookie、账号信息缓存）。\n\n清除后需要重新授权才能浏览/播放，并将退出播放器。"
            }
        val positiveText = if (deleteLibrary) "确认删除" else "确认清除"

        CommonDialog
            .Builder(activity)
            .apply {
                tips = "提示"
                this.content = content
                addPositive(positiveText) {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        val currentKey = Cloud115AuthStore.storageKey(editLibrary)
                        val safeCookie =
                            runCatching { Cloud115Headers.redactCookie(Cloud115AuthStore.read(currentKey).cookie) }
                                .getOrDefault("")

                        val result =
                            runCatching {
                                val storedLibrary = MediaLibraryRepository.getByUrl(editLibrary.url, editLibrary.mediaType)
                                val storedKey = storedLibrary?.let { Cloud115AuthStore.storageKey(it) }

                                if (!storedKey.isNullOrBlank()) {
                                    Cloud115AuthStore.clear(storedKey)
                                }
                                if (currentKey != storedKey) {
                                    Cloud115AuthStore.clear(currentKey)
                                }

                                if (deleteLibrary) {
                                    storedLibrary?.let {
                                        if (it.id > 0) {
                                            MediaLibraryCredentialStore.clear(it.id)
                                        }
                                        MediaLibraryRepository.delete(it.url, it.mediaType)
                                    }
                                }

                                PlayerActions.sendExitPlayer(activity, libraryId)
                            }

                        result.onFailure { t ->
                            LogFacade.e(
                                LogModule.STORAGE,
                                LOG_TAG,
                                "disconnect failed",
                                mapOf(
                                    "libraryId" to libraryId.toString(),
                                    "deleteLibrary" to deleteLibrary.toString(),
                                    "storageKey" to currentKey,
                                    "cookie" to safeCookie,
                                    "exception" to t::class.java.simpleName,
                                ),
                                t,
                            )
                            ErrorReportHelper.postCatchedExceptionWithContext(
                                t,
                                "Cloud115StorageEditDialog",
                                "disconnect",
                                "libraryId=$libraryId deleteLibrary=$deleteLibrary storageKey=$currentKey cookie=$safeCookie",
                            )
                            withContext(Dispatchers.Main) {
                                val message = t.message?.takeIf { it.isNotBlank() } ?: "操作失败"
                                ToastCenter.showError(message)
                            }
                        }

                        result.onSuccess {
                            LogFacade.i(
                                LogModule.STORAGE,
                                LOG_TAG,
                                "disconnect success",
                                mapOf(
                                    "libraryId" to libraryId.toString(),
                                    "deleteLibrary" to deleteLibrary.toString(),
                                    "storageKey" to currentKey,
                                ),
                            )
                            withContext(Dispatchers.Main) {
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
                    }
                }
                addNegative()
            }.build()
            .show()
    }

    private fun refreshAuthViews() {
        binding.urlValueTv.text = editLibrary.url.ifBlank { "未绑定账号" }

        val storageKey = Cloud115AuthStore.storageKey(editLibrary)
        val stateResult = runCatching { Cloud115AuthStore.read(storageKey) }
        val state = stateResult.getOrNull()
        stateResult.exceptionOrNull()?.let { t ->
            LogFacade.w(
                LogModule.STORAGE,
                LOG_TAG,
                "read auth state failed",
                mapOf(
                    "storageKey" to storageKey,
                    "exception" to t::class.java.simpleName,
                ),
                t,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "Cloud115StorageEditDialog",
                "refreshAuthViews",
                "storageKey=$storageKey",
            )
        }

        val isAuthorized = state?.isAuthorized() == true
        val userId =
            editLibrary.url
                .substringAfter("115cloud://uid/", missingDelimiterValue = "")
                .trim()

        binding.authStatusTv.text =
            if (isAuthorized) {
                "已授权" + (if (userId.isNotBlank()) "（userId=$userId）" else "")
            } else {
                "未授权"
            }
        binding.authActionTv.text = if (isAuthorized) "重新授权" else "授权"

        binding.hintTv.isVisible = true
        binding.disconnectTv.isVisible = (activity.editData?.id ?: editLibrary.id) > 0
    }

    private fun handleAuthSuccess(
        userId: String,
        cookieHeader: String,
        loginApp: String?,
        userName: String?,
        avatarUrl: String?,
        authMethod: String
    ) {
        val safeCookie = Cloud115Headers.redactCookie(cookieHeader)
        runCatching {
            val oldStorageKey =
                editLibrary.url
                    .takeIf { it.isNotBlank() }
                    ?.let { Cloud115AuthStore.storageKey(editLibrary) }

            val url = buildLibraryUrl(userId)
            editLibrary.url = url

            if (editLibrary.displayName.isBlank()) {
                editLibrary.displayName = userName?.takeIf { it.isNotBlank() } ?: "115 Cloud"
            }

            val nowMs = System.currentTimeMillis()
            val newStorageKey = Cloud115AuthStore.storageKey(editLibrary)
            Cloud115AuthStore.writeAuthorized(
                storageKey = newStorageKey,
                cookie = cookieHeader,
                userId = userId,
                loginApp = loginApp,
                userName = userName,
                avatarUrl = avatarUrl,
                updatedAtMs = nowMs,
            )

            if (!oldStorageKey.isNullOrBlank() && oldStorageKey != newStorageKey) {
                Cloud115AuthStore.clear(oldStorageKey)
            }

            LogFacade.i(
                LogModule.STORAGE,
                LOG_TAG,
                "auth success",
                mapOf(
                    "authMethod" to authMethod,
                    "isEditMode" to (originalLibrary != null).toString(),
                    "libraryId" to (activity.editData?.id ?: editLibrary.id).toString(),
                    "oldStorageKey" to oldStorageKey.orEmpty(),
                    "newStorageKey" to newStorageKey,
                    "userId" to userId,
                    "loginApp" to loginApp.orEmpty(),
                    "cookie" to safeCookie,
                ),
            )

            ToastCenter.showOriginalToast("授权成功")
            val saveJob = autoSaveHelper.flush()
            refreshAuthViews()
            if (saveJob != null) {
                activity.lifecycleScope.launch {
                    saveJob.join()
                    refreshAuthViews()
                }
            }
        }.onFailure { t ->
            LogFacade.e(
                LogModule.STORAGE,
                LOG_TAG,
                "auth success handler failed",
                mapOf(
                    "authMethod" to authMethod,
                    "isEditMode" to (originalLibrary != null).toString(),
                    "libraryId" to (activity.editData?.id ?: editLibrary.id).toString(),
                    "userId" to userId,
                    "cookie" to safeCookie,
                    "exception" to t::class.java.simpleName,
                ),
                t,
            )
            ErrorReportHelper.postCatchedExceptionWithContext(
                t,
                "Cloud115StorageEditDialog",
                "handleAuthSuccess",
                "authMethod=$authMethod isEditMode=${originalLibrary != null} libraryId=${activity.editData?.id ?: editLibrary.id} userId=$userId cookie=$safeCookie",
            )
            val message = t.message?.takeIf { it.isNotBlank() } ?: "授权失败"
            ToastCenter.showError(message)
        }
    }

    private fun buildLibraryIfValid(showToast: Boolean): MediaLibraryEntity? {
        if (!allowAutoSave) {
            return null
        }

        val url = editLibrary.url.trim().removeSuffix("/")
        val isValid = Regex("^115cloud://uid/\\d+$").matches(url)
        if (!isValid) {
            if (showToast) {
                ToastCenter.showWarning("保存失败，请先完成授权")
            }
            return null
        }

        val displayName = editLibrary.displayName.trim().ifEmpty { "115 Cloud" }
        return editLibrary.copy(
            displayName = displayName,
            url = url,
        )
    }

    private fun buildLibraryUrl(userId: String): String = "115cloud://uid/${userId.trim()}"

    private enum class AuthMethod {
        QRCODE,
        TOKEN
    }

    private enum class DisconnectAction {
        CLEAR_AUTH,
        CLEAR_AUTH_AND_DELETE_LIBRARY
    }

    companion object {
        private const val LOG_TAG = "cloud115_storage_edit"
        private const val DEFAULT_LOGIN_APP: String = "tv"
    }
}
