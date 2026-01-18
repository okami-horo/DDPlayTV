package com.xyoye.storage_component.ui.dialog

import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogRemoteLoginBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity

/**
 * Created by xyoye on 2021/3/25.
 */

class RemoteStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalStorage: MediaLibraryEntity?
) : StorageEditDialog<DialogRemoteLoginBinding>(activity) {
    private var remoteData: MediaLibraryEntity
    private var tokenRequired = false

    private lateinit var binding: DialogRemoteLoginBinding

    /*
    private val scanActivityLauncher = ScanActivityLauncher(activity, onResult())
     */

    init {
        remoteData = originalStorage ?: MediaLibraryEntity(
            0,
            "",
            "",
            MediaType.REMOTE_STORAGE,
            port = 80,
        )
    }

    override fun getChildLayoutId() = R.layout.dialog_remote_login

    override fun initView(binding: DialogRemoteLoginBinding) {
        this.binding = binding
        val isEditStorage = originalStorage != null

        setTitle(if (isEditStorage) "编辑PC端媒体库帐号" else "添加PC端媒体库帐号")
        binding.remoteData = remoteData

        setGroupMode(remoteData.remoteAnimeGrouping)

        // TV 端移除扫码入口，隐藏按钮并保留原实现注释
        binding.scanLl.isVisible = false
        binding.scanLl.setOnClickListener(null)

        val autoSaveHelper =
            StorageAutoSaveHelper(
                coroutineScope = activity.lifecycleScope,
                buildLibrary = { buildLibraryIfValid(remoteData, showToast = false) },
                onSave = { saveStorage(it, showToast = false) },
            )
        registerAutoSaveHelper(autoSaveHelper)

        PlayerTypeOverrideBinder.bind(
            binding.playerTypeOverrideLayout,
            remoteData,
            onChanged = { autoSaveHelper.requestSave() },
        )
        autoSaveHelper.markSaved(buildLibraryIfValid(remoteData, showToast = false))

        binding.addressEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.displayNameEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })
        binding.secretEt.addTextChangedListener(afterTextChanged = { autoSaveHelper.requestSave() })

        /*
        binding.scanLl.setOnClickListener {
            DanDanPlay.permission.camera.request(activity) {
                onGranted {
                    scanActivityLauncher.launch()
                }
                onDenied {
                    ToastCenter.showError("获取相机权限失败，无法进行扫码")
                }
            }
        }
         */

        binding.serverTestConnectTv.setOnClickListener {
            val testLibrary = buildLibraryIfValid(remoteData, showToast = true) ?: return@setOnClickListener
            activity.testStorage(testLibrary)
        }

        binding.tvGroupByFile.setOnClickListener {
            remoteData.remoteAnimeGrouping = false
            setGroupMode(false)
            autoSaveHelper.requestSave()
        }

        binding.tvGroupByAnime.setOnClickListener {
            remoteData.remoteAnimeGrouping = true
            setGroupMode(true)
            autoSaveHelper.requestSave()
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
        remoteData: MediaLibraryEntity,
        showToast: Boolean,
    ): MediaLibraryEntity? {
        val url = remoteData.url.trim()
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

        if (tokenRequired && remoteData.remoteSecret.isNullOrEmpty()) {
            if (showToast) {
                ToastCenter.showWarning("请填写API密钥")
            }
            return null
        }

        val displayName = remoteData.displayName.ifEmpty { "PC端媒体库" }
        return remoteData.copy(
            displayName = displayName,
            url = normalizedUrl,
            describe = normalizedUrl,
        )
    }

    private fun setGroupMode(isGroupByAnime: Boolean) {
        binding.tvGroupByAnime.isSelected = isGroupByAnime
        binding.tvGroupByAnime.setTextColorRes(
            if (isGroupByAnime) R.color.text_white else R.color.text_black,
        )

        binding.tvGroupByFile.isSelected = !isGroupByAnime
        binding.tvGroupByFile.setTextColorRes(
            if (!isGroupByAnime) R.color.text_white else R.color.text_black,
        )
    }

    /*
    private fun onResult() = block@{ data: RemoteScanData? ->
        if (data == null) {
            return@block
        }
        remoteData.url = "http://${data.selectedIP.orEmpty()}:${data.port}/"
        remoteData.displayName = data.machineName ?: ""
        tokenRequired = data.tokenRequired
        binding.remoteData = remoteData
    }
     */
}
