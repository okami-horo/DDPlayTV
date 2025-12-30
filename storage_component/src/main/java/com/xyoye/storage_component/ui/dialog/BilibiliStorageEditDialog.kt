package com.xyoye.storage_component.ui.dialog

import com.xyoye.common_component.bilibili.BilibiliApiPreferences
import com.xyoye.common_component.bilibili.BilibiliApiPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliApiType
import com.xyoye.common_component.bilibili.BilibiliDanmakuBlockPreferences
import com.xyoye.common_component.bilibili.BilibiliDanmakuBlockPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferences
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.BilibiliPlayMode
import com.xyoye.common_component.bilibili.BilibiliQuality
import com.xyoye.common_component.bilibili.BilibiliVideoCodec
import com.xyoye.common_component.bilibili.cleanup.BilibiliCleanup
import com.xyoye.common_component.config.PlayerActions
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogBilibiliStorageBinding
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BilibiliStorageEditDialog(
    private val activity: StoragePlusActivity,
    private val originalLibrary: MediaLibraryEntity?
) : StorageEditDialog<DialogBilibiliStorageBinding>(activity) {
    private lateinit var binding: DialogBilibiliStorageBinding

    private lateinit var editLibrary: MediaLibraryEntity
    private var apiPreferences = BilibiliApiPreferences()
    private var preferences = BilibiliPlaybackPreferences()
    private var danmakuBlockPreferences = BilibiliDanmakuBlockPreferences()

    override fun getChildLayoutId() = R.layout.dialog_bilibili_storage

    override fun initView(binding: DialogBilibiliStorageBinding) {
        this.binding = binding
        val isEditMode = originalLibrary != null
        setTitle(if (isEditMode) "编辑Bilibili媒体库" else "添加Bilibili媒体库")

        editLibrary =
            originalLibrary ?: MediaLibraryEntity(
                0,
                "",
                Api.BILI_BILI_API,
                MediaType.BILIBILI_STORAGE,
            )
        if (editLibrary.url.isBlank()) {
            editLibrary.url = Api.BILI_BILI_API
        }
        binding.library = editLibrary

        apiPreferences = BilibiliApiPreferencesStore.read(editLibrary)
        preferences = BilibiliPlaybackPreferencesStore.read(editLibrary)
        danmakuBlockPreferences = BilibiliDanmakuBlockPreferencesStore.read(editLibrary)
        refreshPreferenceViews()

        binding.apiTypeActionTv.setOnClickListener { showApiTypeDialog() }
        binding.playModeActionTv.setOnClickListener { showPlayModeDialog() }
        binding.qualityActionTv.setOnClickListener { showQualityDialog() }
        binding.codecActionTv.setOnClickListener { showCodecDialog() }
        binding.allow4kOnTv.setOnClickListener { updateAllow4k(true) }
        binding.allow4kOffTv.setOnClickListener { updateAllow4k(false) }
        binding.aiBlockOnTv.setOnClickListener { updateAiBlock(true) }
        binding.aiBlockOffTv.setOnClickListener { updateAiBlock(false) }
        binding.aiLevelActionTv.setOnClickListener { showAiLevelDialog() }

        binding.disconnectTv.isVisible = isEditMode && editLibrary.id > 0
        binding.disconnectTv.setOnClickListener {
            val libraryId = editLibrary.id
            if (libraryId <= 0) return@setOnClickListener
            CommonDialog
                .Builder(activity)
                .apply {
                    tips = "提示"
                    content = "确认断开连接并清除该媒体库的隐私数据？\n\n将清除：Cookie/登录态、API类型偏好、播放偏好、弹幕屏蔽偏好、播放历史/进度、Bilibili 弹幕缓存文件。"
                    positiveText = "确认清除"
                    addPositive { dialog ->
                        dialog.dismiss()
                        activity.lifecycleScope.launch {
                            BilibiliCleanup.cleanup(editLibrary)
                            PlayerActions.sendExitPlayer(activity, libraryId)
                            ToastCenter.showOriginalToast("已断开并清除数据")
                            dismiss()
                        }
                    }
                    addNegative()
                }.build()
                .show()
        }

        addNeutralButton("恢复默认") {
            apiPreferences = BilibiliApiPreferences()
            preferences = BilibiliPlaybackPreferences()
            danmakuBlockPreferences = BilibiliDanmakuBlockPreferences()
            refreshPreferenceViews()
        }

        setPositiveListener {
            if (editLibrary.displayName.isBlank()) {
                editLibrary.displayName = "Bilibili媒体库"
            }
            if (editLibrary.url.isBlank()) {
                editLibrary.url = Api.BILI_BILI_API
            }
            if (preferences.preferredQualityQn == BilibiliQuality.QN_4K.qn && !preferences.allow4k) {
                preferences = preferences.copy(allow4k = true)
                refreshPreferenceViews()
            }
            BilibiliApiPreferencesStore.write(editLibrary, apiPreferences)
            BilibiliPlaybackPreferencesStore.write(editLibrary, preferences)
            BilibiliDanmakuBlockPreferencesStore.write(editLibrary, danmakuBlockPreferences)
            activity.addStorage(editLibrary)
        }

        setNegativeListener {
            activity.finish()
        }
    }

    override fun onTestResult(result: Boolean) {
        // Bilibili 媒体库当前不走通用 testStorage 测试流程，保持空实现
    }

    private fun updateAllow4k(enabled: Boolean) {
        preferences = preferences.copy(allow4k = enabled)
        refreshPreferenceViews()
    }

    private fun refreshPreferenceViews() {
        binding.apiTypeValueTv.text = apiPreferences.apiType.label
        binding.playModeValueTv.text = preferences.playMode.label
        binding.qualityValueTv.text = BilibiliQuality.fromQn(preferences.preferredQualityQn).label
        binding.codecValueTv.text = preferences.preferredVideoCodec.label
        setAllow4kSelected(preferences.allow4k)

        binding.aiLevelValueTv.text = formatAiLevel(danmakuBlockPreferences.aiLevel)
        setAiBlockSelected(danmakuBlockPreferences.aiSwitch)
    }

    private fun showApiTypeDialog() {
        val actions =
            BilibiliApiType.entries.map {
                val describe =
                    when (it) {
                        BilibiliApiType.WEB -> "默认：网页接口（Cookie + WBI）"
                        BilibiliApiType.TV -> "TV 客户端接口（AppKey 签名），部分场景可能需要重新扫码登录"
                    }
                SheetActionBean(
                    actionId = it,
                    actionName = it.label,
                    describe = describe,
                )
            }
        BottomActionDialog(activity, actions, "API类型") {
            val selected = it.actionId as? BilibiliApiType ?: return@BottomActionDialog false
            apiPreferences = apiPreferences.copy(apiType = selected)
            refreshPreferenceViews()
            true
        }.show()
    }

    private fun setAllow4kSelected(allow: Boolean) {
        binding.allow4kOnTv.isSelected = allow
        binding.allow4kOnTv.setTextColorRes(if (allow) R.color.text_white else R.color.text_black)

        binding.allow4kOffTv.isSelected = !allow
        binding.allow4kOffTv.setTextColorRes(if (!allow) R.color.text_white else R.color.text_black)
    }

    private fun updateAiBlock(enabled: Boolean) {
        danmakuBlockPreferences = danmakuBlockPreferences.copy(aiSwitch = enabled)
        refreshPreferenceViews()
    }

    private fun setAiBlockSelected(enabled: Boolean) {
        binding.aiBlockOnTv.isSelected = enabled
        binding.aiBlockOnTv.setTextColorRes(if (enabled) R.color.text_white else R.color.text_black)

        binding.aiBlockOffTv.isSelected = !enabled
        binding.aiBlockOffTv.setTextColorRes(if (!enabled) R.color.text_white else R.color.text_black)
    }

    private fun showAiLevelDialog() {
        val actions =
            (0..10).map { level ->
                SheetActionBean(
                    actionId = level,
                    actionName = formatAiLevel(level),
                )
            }
        BottomActionDialog(activity, actions, "屏蔽等级") {
            val selected = it.actionId as? Int ?: return@BottomActionDialog false
            danmakuBlockPreferences = danmakuBlockPreferences.copy(aiLevel = selected.coerceIn(0, 10))
            refreshPreferenceViews()
            true
        }.show()
    }

    private fun formatAiLevel(level: Int): String =
        if (level == 0) {
            "默认（3）"
        } else {
            level.coerceIn(0, 10).toString()
        }

    private fun showPlayModeDialog() {
        val actions =
            BilibiliPlayMode.entries.map {
                SheetActionBean(
                    actionId = it,
                    actionName = it.label,
                )
            }
        BottomActionDialog(activity, actions, "取流模式") {
            val selected = it.actionId as? BilibiliPlayMode ?: return@BottomActionDialog false
            preferences = preferences.copy(playMode = selected)
            refreshPreferenceViews()
            true
        }.show()
    }

    private fun showQualityDialog() {
        val actions =
            BilibiliQuality.entries.map {
                val describe =
                    when (it) {
                        BilibiliQuality.AUTO -> "不强制画质，按服务端默认/可用性选择"
                        BilibiliQuality.QN_4K -> "需要 allow4k=开启，且可能需要大会员"
                        BilibiliQuality.QN_1080P_PLUS -> "可能需要大会员"
                        else -> null
                    }
                SheetActionBean(
                    actionId = it,
                    actionName = it.label,
                    describe = describe,
                )
            }
        BottomActionDialog(activity, actions, "画质优先") {
            val selected = it.actionId as? BilibiliQuality ?: return@BottomActionDialog false
            var next = preferences.copy(preferredQualityQn = selected.qn)
            if (selected == BilibiliQuality.QN_4K) {
                next = next.copy(allow4k = true)
            }
            preferences = next
            refreshPreferenceViews()
            true
        }.show()
    }

    private fun showCodecDialog() {
        val actions =
            BilibiliVideoCodec.entries.map {
                val describe =
                    when (it) {
                        BilibiliVideoCodec.AVC -> "兼容性最好（推荐默认）"
                        BilibiliVideoCodec.HEVC -> "更省带宽，但设备需支持 H.265"
                        BilibiliVideoCodec.AV1 -> "更省带宽，但设备需支持 AV1"
                        else -> null
                    }
                SheetActionBean(
                    actionId = it,
                    actionName = it.label,
                    describe = describe,
                )
            }
        BottomActionDialog(activity, actions, "视频编码") {
            val selected = it.actionId as? BilibiliVideoCodec ?: return@BottomActionDialog false
            preferences = preferences.copy(preferredVideoCodec = selected)
            refreshPreferenceViews()
            true
        }.show()
    }
}
