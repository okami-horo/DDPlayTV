package com.xyoye.local_component.ui.metadata

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.metadata.metadataFailureText
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MetadataMatchViewModel : BaseViewModel() {
    val fileName = MutableLiveData("")
    val status = MutableLiveData("正在读取文件…")
    val advice = MutableLiveData("")
    val canRetry = MutableLiveData(false)
    val canRefresh = MutableLiveData(false)
    val busy = MutableLiveData(false)
    private var selectedId = 0L
    private var initialized = false

    fun initialize(
        assetId: Long,
        libraryId: Int,
        reference: PlaybackFileReference
    ) {
        if (initialized) return
        initialized = true
        val dao = MetadataCoordinator.repository.dao
        // Opening or re-subscribing to this page never starts a recognition attempt.
        viewModelScope.launch {
            combine(dao.observeAssets(), dao.observeBindings(), dao.observeWorks()) { assets, bindings, works ->
                val asset =
                    assets.find {
                        if (assetId >
                            0
                        ) {
                            it.assetId == assetId
                        } else {
                            it.libraryId == libraryId && it.uniqueKey == reference.uniqueKey
                        }
                    }
                val binding = asset?.let { target -> bindings.find { it.assetId == target.assetId } }
                val work = binding?.let { target -> works.find { it.mediaKind == target.mediaKind && it.tmdbId == target.tmdbId } }
                Triple(asset, binding, work)
            }.collect { (asset, binding, work) ->
                selectedId = asset?.assetId ?: 0
                fileName.value = asset?.fileName ?: reference.displayTitle.orEmpty()
                canRetry.value = asset != null
                canRefresh.value = work != null
                if (busy.value != true) {
                    status.value =
                        when {
                            asset == null -> "尚无文件资料"
                            binding?.state == "IGNORED" -> "已忽略 · 重新识别成功后可恢复入库"
                            binding?.failure != null -> if (work != null) "识别未完成 · 已保留《${work.title}》" else "尚未识别"
                            work != null -> "已识别 · ${work.title}"
                            else -> "尚未识别"
                        }
                    advice.value = if (asset == null) "请先播放文件，或从来源目录主动扫描资料。" else metadataFailureText(binding?.failure)
                }
            }
        }
    }

    fun recognizeAgain() = mutate(false)

    fun refresh() = mutate(true)

    private fun mutate(refresh: Boolean) {
        val id = selectedId.takeIf { it > 0 } ?: return
        if (busy.value == true) return
        busy.value = true
        status.value = if (refresh) "正在刷新资料…" else "正在重新识别…"
        advice.value = "任务在后台执行，可以返回继续浏览或播放。"
        var outcome = ""
        var detail = ""
        MetadataCoordinator.submit({
            if (refresh) MetadataCoordinator.refresh(MetadataCoordinator.prepare(id)) else MetadataCoordinator.recognizeAgain(id)
            val repository = MetadataCoordinator.repository
            val binding = repository.dao.binding(id)
            val kind = binding?.mediaKind
            val tmdbId = binding?.tmdbId
            val work = if (kind != null && tmdbId != null) repository.dao.work(kind, tmdbId) else null
            outcome =
                if (binding?.state == "MATCHED" && binding.failure == null) {
                    "${if (refresh) "资料已刷新" else "已识别"} · ${work?.title.orEmpty()}"
                } else {
                    "识别未完成${if (work != null) " · 已保留《${work.title}》" else ""}"
                }
            detail = metadataFailureText(binding?.failure)
        }) { result ->
            busy.value = false
            status.value = if (result.isSuccess) outcome else "操作未完成"
            advice.value = result.exceptionOrNull()?.let(::metadataError) ?: detail
        }
    }
}

fun metadataError(error: Throwable): String = metadataFailureText(MetadataCoordinator.failureCategory(error))
