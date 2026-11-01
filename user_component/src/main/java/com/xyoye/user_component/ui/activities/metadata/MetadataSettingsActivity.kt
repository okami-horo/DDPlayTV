package com.xyoye.user_component.ui.activities.metadata

import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.view.ItemDecorationSpace
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.entity.MetadataScanRun
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityMetadataSettingsBinding
import com.xyoye.user_component.databinding.ItemMetadataTaskBinding

@Route(path = RouteTable.User.MetadataSettings)
class MetadataSettingsActivity : BaseActivity<MetadataSettingsViewModel, ActivityMetadataSettingsBinding>() {
    private var initialFocus = true

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataSettingsViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_settings

    override fun initView() {
        dataBinding.configuration.text = (if (viewModel.configured) "TMDB 资料服务已配置" else "TMDB 资料服务尚未配置，仍可浏览与播放文件") +
            "\n播放后自动识别当前文件与同目录同剧。也可从来源主动扫描目录。\n任务仅在应用运行期间执行；资料语言优先中文。"
        dataBinding.tasks.apply {
            layoutManager = vertical()
            // DpadRecyclerView 1.4.3 omits margins when sizing its selected row.
            addItemDecoration(ItemDecorationSpace((4 * resources.displayMetrics.density).toInt()))
            setFocusOutAllowed(true, true)
            nextFocusDownId = dataBinding.clearCache.id
            adapter =
                buildAdapter {
                    setupDiffUtil { areItemsTheSame { old, new -> (old as MetadataScanRun).runId == (new as MetadataScanRun).runId } }
                    addItem<MetadataScanRun, ItemMetadataTaskBinding>(R.layout.item_metadata_task) {
                        initView { run, _, _ ->
                            val running = run.state in setOf("RUNNING", "QUEUED", "WAITING_NETWORK")
                            itemBinding.title.text = "来源 ${run.libraryId} · ${stateText(run.state)} · ${if (running) "取消任务" else "重试"}"
                            itemBinding.summary.text =
                                "${if (run.triggerKey != null) "播放触发 · 当前文件及同目录同剧" else "主动扫描${if (run.recursive) "（含子目录）" else "（仅当前目录）"}"}\n" +
                                "${run.triggerName ?: run.directory.ifBlank { "来源根目录" }}\n" +
                                "发现 ${run.discovered} · 已入库 ${run.matched} · 未识别 ${run.pending} · 失败 ${run.failed}" +
                                (run.failure?.let { "\n原因：${failureText(it)}" } ?: "")
                            itemBinding.root.setOnClickListener {
                                CommonDialog
                                    .Builder(this@MetadataSettingsActivity)
                                    .apply {
                                        content =
                                            if (running) {
                                                "取消此任务？已保存资料保留。"
                                            } else if (run.triggerKey !=
                                                null
                                            ) {
                                                "重新识别当前文件并检查同目录同剧？只有确定的新结果才会替换旧关联。"
                                            } else {
                                                "重新读取该范围？中断目录从第一页开始，已有人工关联保留。"
                                            }
                                        addPositive { dialog ->
                                            dialog.dismiss()
                                            if (running) viewModel.cancel(run) else viewModel.retry(run)
                                        }
                                        addNegative()
                                    }.build()
                                    .show()
                            }
                        }
                    }
                }
        }
        viewModel.scans.collectAtStarted(this) {
            val hadListFocus = dataBinding.tasks.hasFocus()
            dataBinding.tasks.setData(it)
            dataBinding.empty.visibility = if (it.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            dataBinding.tasks.visibility = if (it.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            if (it.isEmpty() && hadListFocus) dataBinding.back.requestFocus()
            if (initialFocus) {
                initialFocus = false
                if (it.isEmpty()) dataBinding.back.requestFocus() else dataBinding.tasks.requestFocus()
            }
        }
        viewModel.message.observe(this) { dataBinding.message.text = it }
        dataBinding.clearCache.setOnClickListener { viewModel.clearHttpCache() }
        dataBinding.clearImages.setOnClickListener { viewModel.clearImages() }
        dataBinding.back.setOnClickListener { finish() }
        dataBinding.back.requestFocus()
    }

    private fun stateText(state: String): String =
        when (state) {
            "RUNNING" -> "扫描中"
            "QUEUED" -> "排队中"
            "COMPLETED" -> "范围读取完成"
            "PARTIAL" -> "部分完成"
            "CANCELLED" -> "已中断"
            "WAITING_NETWORK" -> "等待网络"
            else -> "失败"
        }

    private fun failureText(failure: String): String =
        com.xyoye.common_component.metadata
            .metadataFailureText(failure)
}
