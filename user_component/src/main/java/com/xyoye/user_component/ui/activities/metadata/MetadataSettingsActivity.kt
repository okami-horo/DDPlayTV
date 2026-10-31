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
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.entity.MetadataScanRun
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityMetadataSettingsBinding
import com.xyoye.user_component.databinding.ItemMetadataTaskBinding

@Route(path = RouteTable.User.MetadataSettings)
class MetadataSettingsActivity : BaseActivity<MetadataSettingsViewModel, ActivityMetadataSettingsBinding>() {
    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataSettingsViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_settings

    override fun initView() {
        dataBinding.configuration.text = (if (viewModel.configured) "TMDB 资料服务已配置" else "TMDB 资料服务尚未配置，仍可浏览与播放文件") +
            "\n资料语言：中文，缺失文本回退原名 / 英文。成人搜索关闭。\n在来源目录选择“扫描此目录资料”；任务仅在应用运行期间执行。"
        dataBinding.tasks.apply {
            layoutManager = vertical()
            adapter =
                buildAdapter {
                    setupDiffUtil { areItemsTheSame { old, new -> (old as MetadataScanRun).runId == (new as MetadataScanRun).runId } }
                    addItem<MetadataScanRun, ItemMetadataTaskBinding>(R.layout.item_metadata_task) {
                        initView { run, _, _ ->
                            val running = run.state in setOf("RUNNING", "QUEUED", "WAITING_NETWORK")
                            itemBinding.title.text = "来源 ${run.libraryId} · ${stateText(run.state)} · ${if (running) "确认键取消" else "确认键重试"}"
                            itemBinding.summary.text =
                                "${run.directory.ifBlank { "来源根目录" }}${if (run.recursive) "（含子目录）" else ""}\n" +
                                "发现 ${run.discovered} · 已匹配 ${run.matched} · 待确认 ${run.pending} · 失败 ${run.failed} · 完整目录 ${run.completedDirectories}" +
                                (run.failure?.let { "\n原因：${failureText(it)}" } ?: "")
                            itemBinding.root.setOnClickListener {
                                CommonDialog
                                    .Builder(this@MetadataSettingsActivity)
                                    .apply {
                                        content = if (running) "取消此任务？已保存资料保留，未完整读取的目录不会标记文件缺失。" else "重新读取该范围？中断目录从第一页开始，人工关联保留。"
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
            if (it.isEmpty() && hadListFocus) dataBinding.clearCache.requestFocus()
        }
        viewModel.message.observe(this) { dataBinding.message.text = it }
        dataBinding.clearCache.setOnClickListener { viewModel.clearHttpCache() }
        dataBinding.clearImages.setOnClickListener { viewModel.clearImages() }
        dataBinding.back.setOnClickListener { finish() }
        dataBinding.clearCache.requestFocus()
    }

    private fun stateText(state: String): String =
        when (state) {
            "RUNNING" -> "扫描中"
            "QUEUED" -> "排队中"
            "COMPLETED" -> "范围读取完成"
            "CANCELLED" -> "已中断"
            "WAITING_NETWORK" -> "等待网络"
            else -> "失败"
        }

    private fun failureText(failure: String): String =
        when (failure) {
            "NOT_CONFIGURED" -> "TMDB 未配置"
            "AUTHENTICATION" -> "TMDB 访问权限错误"
            "RATE_LIMIT" -> "服务限流，请稍后重试"
            "RESULT_EXPIRED" -> "来源已变更"
            "INTERRUPTED" -> "上次应用退出，需手动重试"
            else -> "网络、目录或数据库操作失败"
        }
}
