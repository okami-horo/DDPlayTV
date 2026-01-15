package com.xyoye.storage_component.ui.dialog

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.repository.Open115ProApiException
import com.xyoye.common_component.network.repository.Open115Repository
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.storage.open115.net.Open115Headers
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.data.open115.Open115UserInfoData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogOpen115StorageBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class Open115StorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalLibrary: MediaLibraryEntity?
) : StorageEditDialog<DialogOpen115StorageBinding>(activity) {
    private lateinit var binding: DialogOpen115StorageBinding

    private lateinit var editLibrary: MediaLibraryEntity
    private var lastResolved: ResolvedAuth? = null

    override fun getChildLayoutId() = R.layout.dialog_open115_storage

    override fun initView(binding: DialogOpen115StorageBinding) {
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
        setTitle(if (isEditMode) "编辑115 Open存储库" else "添加115 Open存储库")

        editLibrary =
            originalLibrary ?: MediaLibraryEntity(
                id = 0,
                displayName = "",
                url = "",
                mediaType = MediaType.OPEN_115_STORAGE
            )

        binding.library = editLibrary
        PlayerTypeOverrideBinder.bind(binding.playerTypeOverrideLayout, editLibrary)

        bindTokenToggle(binding.accessTokenEt, binding.accessTokenToggleIv)
        bindTokenToggle(binding.refreshTokenEt, binding.refreshTokenToggleIv)

        if (isEditMode) {
            val state = Open115AuthStore.read(Open115AuthStore.storageKey(editLibrary))
            state.accessToken?.takeIf { it.isNotBlank() }?.let { binding.accessTokenEt.setText(it) }
            state.refreshToken?.takeIf { it.isNotBlank() }?.let { binding.refreshTokenEt.setText(it) }
        }

        binding.serverTestConnectTv.setOnClickListener { testConnection() }

        binding.disconnectTv.isVisible = isEditMode && editLibrary.id > 0
        binding.disconnectTv.setOnClickListener { showDisconnectConfirmDialog() }

        setPositiveListener { saveLibrary() }
        setNegativeListener { activity.finish() }

        refreshTestStatus(null)
    }

    override fun onTestResult(result: Boolean) {
        // Open115 storage does not use the shared testStorage flow.
    }

    private fun testConnection() {
        val (accessToken, refreshToken) = readTokensOrWarn() ?: return

        LogFacade.d(
            LogModule.STORAGE,
            LOG_TAG,
            "test connection",
            mapOf(
                "isEditMode" to (originalLibrary != null).toString(),
                "libraryId" to editLibrary.id.toString(),
                "accessToken" to Open115Headers.redactToken(accessToken),
                "refreshToken" to Open115Headers.redactToken(refreshToken),
            ),
        )
        refreshTestStatus(TestStatus.Testing)
        activity.lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { resolveAuth(accessToken, refreshToken) }
                }

            val resolved = result.getOrNull()
            if (resolved == null) {
                lastResolved = null
                refreshTestStatus(TestStatus.Failed)
                result.exceptionOrNull()?.let { t ->
                    LogFacade.e(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "test connection failed",
                        buildMap {
                            put("isEditMode", (originalLibrary != null).toString())
                            put("libraryId", editLibrary.id.toString())
                            put("accessToken", Open115Headers.redactToken(accessToken))
                            put("refreshToken", Open115Headers.redactToken(refreshToken))
                            put("exception", t::class.java.simpleName)
                        },
                        t,
                    )
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        t,
                        "Open115StorageEditDialog",
                        "testConnection",
                        "isEditMode=${originalLibrary != null} libraryId=${editLibrary.id} accessToken=${Open115Headers.redactToken(accessToken)} refreshToken=${Open115Headers.redactToken(refreshToken)}",
                    )
                }
                val message = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "连接失败"
                ToastCenter.showError(message)
                return@launch
            }

            lastResolved = resolved
            applyResolved(resolved)
            refreshTestStatus(TestStatus.Success)
            LogFacade.i(
                LogModule.STORAGE,
                LOG_TAG,
                "test connection success",
                mapOf(
                    "isEditMode" to (originalLibrary != null).toString(),
                    "libraryId" to editLibrary.id.toString(),
                    "uid" to resolved.uid,
                ),
            )
        }
    }

    private fun saveLibrary() {
        val (accessToken, refreshToken) = readTokensOrWarn() ?: return

        LogFacade.d(
            LogModule.STORAGE,
            LOG_TAG,
            "save library",
            mapOf(
                "isEditMode" to (originalLibrary != null).toString(),
                "libraryId" to editLibrary.id.toString(),
                "accessToken" to Open115Headers.redactToken(accessToken),
                "refreshToken" to Open115Headers.redactToken(refreshToken),
            ),
        )
        refreshTestStatus(TestStatus.Testing)
        activity.lifecycleScope.launch {
            val resolved =
                lastResolved
                    ?.takeIf { it.accessToken == accessToken && it.refreshToken == refreshToken }
                    ?: withContext(Dispatchers.IO) {
                        runCatching { resolveAuth(accessToken, refreshToken) }.getOrNull()
                    }

            if (resolved == null) {
                lastResolved = null
                refreshTestStatus(TestStatus.Failed)
                LogFacade.w(
                    LogModule.STORAGE,
                    LOG_TAG,
                    "save library failed: resolve auth null",
                    mapOf(
                        "isEditMode" to (originalLibrary != null).toString(),
                        "libraryId" to editLibrary.id.toString(),
                    ),
                )
                ToastCenter.showError("保存失败，token 校验未通过")
                return@launch
            }

            val url = buildLibraryUrl(resolved.uid)
            val duplicate =
                withContext(Dispatchers.IO) {
                    DatabaseManager.instance.getMediaLibraryDao().getByUrl(url, MediaType.OPEN_115_STORAGE)
                }
            if (duplicate != null && duplicate.id != editLibrary.id) {
                refreshTestStatus(TestStatus.Success)
                LogFacade.w(
                    LogModule.STORAGE,
                    LOG_TAG,
                    "duplicate library detected",
                    mapOf(
                        "libraryId" to editLibrary.id.toString(),
                        "duplicateId" to duplicate.id.toString(),
                        "uid" to resolved.uid,
                    ),
                )
                ToastCenter.showError("保存失败，该账号已存在，请使用编辑更新 token")
                return@launch
            }

            val previousKey = originalLibrary?.let { Open115AuthStore.storageKey(it) }

            editLibrary.mediaType = MediaType.OPEN_115_STORAGE
            editLibrary.url = url
            editLibrary.account = null
            editLibrary.password = null
            editLibrary.describe = null
            if (editLibrary.displayName.isBlank()) {
                editLibrary.displayName = resolved.userName?.takeIf { it.isNotBlank() } ?: "115 Open"
            }

            val storageKey = Open115AuthStore.storageKey(editLibrary)
            withContext(Dispatchers.IO) {
                if (!previousKey.isNullOrBlank() && previousKey != storageKey) {
                    Open115AuthStore.clear(previousKey)
                }

                Open115AuthStore.writeTokens(
                    storageKey = storageKey,
                    accessToken = resolved.accessToken,
                    expiresAtMs = resolved.expiresAtMs,
                    refreshToken = resolved.refreshToken
                )
                Open115AuthStore.writeProfile(
                    storageKey = storageKey,
                    uid = resolved.uid,
                    userName = resolved.userName,
                    avatarUrl = resolved.avatarUrl
                )
            }

            LogFacade.i(
                LogModule.STORAGE,
                LOG_TAG,
                "save library success",
                mapOf(
                    "libraryId" to editLibrary.id.toString(),
                    "storageKey" to storageKey,
                    "uid" to resolved.uid,
                ),
            )
            activity.addStorage(editLibrary)
        }
    }

    private fun showDisconnectConfirmDialog() {
        val libraryId = editLibrary.id
        if (libraryId <= 0) return

        CommonDialog
            .Builder(activity)
            .apply {
                tips = "提示"
                content =
                    "确认断开连接并清除授权？\n\n将清除：115 Open 授权信息（access_token/refresh_token、账号信息缓存）。\n\n清除后需要重新填写 token 才能继续浏览/播放。"
                addPositive(positiveText = "确认清除") { dialog ->
                    dialog.dismiss()
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val dao = DatabaseManager.instance.getMediaLibraryDao()
                            val storedLibrary = dao.getById(libraryId)
                            val storedKey = storedLibrary?.let { Open115AuthStore.storageKey(it) }
                            val currentKey = Open115AuthStore.storageKey(editLibrary)

                            if (!storedKey.isNullOrBlank()) {
                                Open115AuthStore.clear(storedKey)
                            }
                            if (currentKey != storedKey) {
                                Open115AuthStore.clear(currentKey)
                            }
                        }

                        LogFacade.i(
                            LogModule.STORAGE,
                            LOG_TAG,
                            "auth cleared",
                            mapOf(
                                "libraryId" to libraryId.toString(),
                            ),
                        )
                        lastResolved = null
                        binding.accessTokenEt.setText("")
                        binding.refreshTokenEt.setText("")
                        refreshTestStatus(null)
                        ToastCenter.showOriginalToast("已清除授权")
                    }
                }
                addNegative()
            }.build()
            .show()
    }

    private fun applyResolved(resolved: ResolvedAuth) {
        val currentAccess = binding.accessTokenEt.text?.toString().orEmpty()
        val currentRefresh = binding.refreshTokenEt.text?.toString().orEmpty()

        if (currentAccess != resolved.accessToken) {
            binding.accessTokenEt.setText(resolved.accessToken)
        }
        if (currentRefresh != resolved.refreshToken) {
            binding.refreshTokenEt.setText(resolved.refreshToken)
        }

        editLibrary.url = buildLibraryUrl(resolved.uid)

        if (editLibrary.displayName.isBlank()) {
            editLibrary.displayName = resolved.userName?.takeIf { it.isNotBlank() } ?: "115 Open"
            binding.displayNameEt.setText(editLibrary.displayName)
        }
    }

    private fun bindTokenToggle(
        editText: AppCompatEditText,
        toggleView: android.widget.ImageView
    ) {
        editText.transformationMethod = PasswordTransformationMethod.getInstance()
        toggleView.isSelected = false
        toggleView.setOnClickListener {
            val visible = !toggleView.isSelected
            toggleView.isSelected = visible
            editText.transformationMethod =
                if (visible) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
            editText.setSelection(editText.text?.length ?: 0)
        }
    }

    private fun readTokensOrWarn(): Pair<String, String>? {
        val accessToken = binding.accessTokenEt.text?.toString().orEmpty().trim()
        val refreshToken = binding.refreshTokenEt.text?.toString().orEmpty().trim()

        if (accessToken.isBlank()) {
            ToastCenter.showWarning("请填写 access_token")
            return null
        }
        if (refreshToken.isBlank()) {
            ToastCenter.showWarning("请填写 refresh_token")
            return null
        }

        return accessToken to refreshToken
    }

    private suspend fun resolveAuth(
        accessToken: String,
        refreshToken: String
    ): ResolvedAuth {
        val userInfo =
            runCatching {
                Open115Repository.userInfo(accessToken).getOrThrow()
            }.getOrElse { t ->
                if (t is Open115ProApiException && Open115Repository.isAuthExpiredCode(t.code)) {
                    LogFacade.w(
                        LogModule.STORAGE,
                        LOG_TAG,
                        "access token expired, refresh token and retry",
                        mapOf(
                            "accessToken" to Open115Headers.redactToken(accessToken),
                            "refreshToken" to Open115Headers.redactToken(refreshToken),
                            "code" to t.code.toString(),
                        ),
                    )
                    val refreshed = Open115Repository.refreshToken(refreshToken).getOrThrow()
                    val token = refreshed.data ?: throw IllegalStateException("refresh_token 返回为空")
                    val refreshedAtMs = System.currentTimeMillis()
                    val expiresAtMs = refreshedAtMs + token.expiresIn * 1000L

                    val retryInfo = Open115Repository.userInfo(token.accessToken).getOrThrow()
                    val retryData = retryInfo.data ?: throw IllegalStateException("userInfo 返回为空")

                    return ResolvedAuth(
                        uid = requireUid(retryData),
                        userName = retryData.userName,
                        avatarUrl = resolveAvatarUrl(retryData),
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken,
                        expiresAtMs = expiresAtMs
                    )
                }
                throw t
            }

        val data = userInfo.data ?: throw IllegalStateException("userInfo 返回为空")
        val placeholderExpiresAtMs = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365)
        return ResolvedAuth(
            uid = requireUid(data),
            userName = data.userName,
            avatarUrl = resolveAvatarUrl(data),
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = placeholderExpiresAtMs
        )
    }

    private fun requireUid(
        data: Open115UserInfoData
    ): String {
        val uid = data.userId?.trim().orEmpty()
        if (uid.isBlank()) {
            throw IllegalStateException("无法获取 uid，请检查 token")
        }
        return uid
    }

    private fun resolveAvatarUrl(
        data: Open115UserInfoData
    ): String? =
        data.userFaceLarge?.takeIf { it.isNotBlank() }
            ?: data.userFaceMedium?.takeIf { it.isNotBlank() }
            ?: data.userFaceSmall?.takeIf { it.isNotBlank() }

    private fun buildLibraryUrl(uid: String): String = "115open://uid/${uid.trim()}"

    private fun refreshTestStatus(status: TestStatus?) {
        binding.serverStatusTv.isVisible = status != null

        when (status) {
            TestStatus.Testing -> {
                binding.serverStatusTv.text = "连接中..."
                binding.serverStatusTv.setTextColorRes(R.color.text_gray)
            }
            TestStatus.Success -> {
                binding.serverStatusTv.text = "连接成功"
                binding.serverStatusTv.setTextColorRes(R.color.text_blue)
            }
            TestStatus.Failed -> {
                binding.serverStatusTv.text = "连接失败"
                binding.serverStatusTv.setTextColorRes(R.color.text_red)
            }
            null -> {
                binding.serverStatusTv.text = ""
            }
        }
    }

    private enum class TestStatus {
        Testing,
        Success,
        Failed
    }

    private data class ResolvedAuth(
        val uid: String,
        val userName: String?,
        val avatarUrl: String?,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long
    )

    companion object {
        private const val LOG_TAG = "open115_storage_edit"
    }
}
