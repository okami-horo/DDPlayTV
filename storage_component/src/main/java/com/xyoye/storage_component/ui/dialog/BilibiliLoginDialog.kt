package com.xyoye.storage_component.ui.dialog

import android.os.SystemClock
import androidx.core.view.isVisible
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.extension.toResColor
import com.xyoye.common_component.utils.QrCodeHelper
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.DialogBilibiliLoginBinding
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BilibiliLoginDialog(
    private val activity: StorageFileActivity,
    private val library: MediaLibraryEntity,
    private val onLoginSuccess: () -> Unit,
) : BaseBottomDialog<DialogBilibiliLoginBinding>(activity) {
    private lateinit var binding: DialogBilibiliLoginBinding

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingJob: Job? = null

    private val storageKey = BilibiliPlaybackPreferencesStore.storageKey(library)
    private val repository = BilibiliRepository(storageKey)

    override fun getChildLayoutId(): Int = R.layout.dialog_bilibili_login

    override fun initView(binding: DialogBilibiliLoginBinding) {
        this.binding = binding

        setTitle("Bilibili 扫码登录")
        setPositiveText("重试")
        setNegativeText("取消")
        setDialogCancelable(touchCancel = false, backPressedCancel = true)

        setPositiveListener { startLoginFlow(forceRefresh = true) }
        setNegativeListener { dismiss() }

        setOnDismissListener {
            pollingJob?.cancel()
            scope.cancel()
        }

        startLoginFlow(forceRefresh = false)
    }

    private fun startLoginFlow(forceRefresh: Boolean) {
        pollingJob?.cancel()
        pollingJob =
            scope.launch {
                binding.loadingPb.isVisible = true
                binding.statusTv.text = "正在获取二维码…"

                val generate = repository.qrcodeGenerate()
                val data = generate.getOrNull()
                if (data == null || data.url.isBlank() || data.qrcodeKey.isBlank()) {
                    binding.loadingPb.isVisible = false
                    binding.statusTv.text = "获取二维码失败，请稍后重试"
                    return@launch
                }

                val qrCode =
                    QrCodeHelper.createQrCode(
                        context = activity,
                        content = data.url,
                        sizePx = dp2px(220),
                        logoResId = R.mipmap.ic_logo,
                        bitmapColor = com.xyoye.common_component.R.color.text_black.toResColor(activity),
                        errorContext = "生成 Bilibili 登录二维码失败",
                    )
                binding.qrCodeIv.setImageBitmap(qrCode)
                binding.loadingPb.isVisible = false
                binding.statusTv.text = "请使用哔哩哔哩 App 扫码登录"

                pollUntilDone(data.qrcodeKey)
            }
    }

    private suspend fun pollUntilDone(qrcodeKey: String) {
        val start = SystemClock.elapsedRealtime()
        while (scope.coroutineContext[Job]?.isActive == true) {
            val poll = repository.qrcodePoll(qrcodeKey)
            val data = poll.getOrNull()
            if (data == null) {
                binding.statusTv.text = "登录状态获取失败，请检查网络后重试"
                return
            }

            when (data.statusCode) {
                86101 -> binding.statusTv.text = "等待扫码…"
                86090 -> binding.statusTv.text = "已扫码，请在 App 上确认登录"
                86038 -> {
                    binding.statusTv.text = "二维码已失效，请点击重试"
                    return
                }

                0 -> {
                    binding.statusTv.text = "登录成功"
                    delay(300)
                    dismiss()
                    onLoginSuccess.invoke()
                    return
                }

                else -> binding.statusTv.text = data.statusMessage ?: "登录中…"
            }

            if (SystemClock.elapsedRealtime() - start > 2 * 60 * 1000) {
                binding.statusTv.text = "二维码已过期，请点击重试"
                return
            }

            delay(1500)
        }
    }
}

