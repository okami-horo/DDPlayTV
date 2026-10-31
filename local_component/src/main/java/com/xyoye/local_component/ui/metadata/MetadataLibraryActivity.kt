package com.xyoye.local_component.ui.metadata

import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.grid
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.ActivityMetadataLibraryBinding

@Route(path = RouteTable.Local.MetadataLibrary)
class MetadataLibraryActivity : BaseActivity<MetadataLibraryViewModel, ActivityMetadataLibraryBinding>() {
    private var filter = "all"
    private var current = MetadataLibraryState()
    private val cards =
        MetadataCardAdapter(posters = true, click = { card ->
            if (card.key.startsWith("asset:")) {
                ARouter
                    .getInstance()
                    .build(
                        RouteTable.Local.MetadataMatch
                    ).withLong("assetId", card.key.substringAfter(':').toLong())
                    .navigation()
            } else {
                ARouter
                    .getInstance()
                    .build(
                        RouteTable.Local.MetadataDetail
                    ).withString("kind", card.key.substringBefore(':'))
                    .withLong("tmdbId", card.key.substringAfter(':').toLong())
                    .navigation()
            }
        })

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataLibraryViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_library

    override fun initView() {
        dataBinding.items.apply {
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            layoutManager = grid(((widthDp - 48) / 164).toInt().coerceIn(2, 8))
            adapter = cards
            setFocusOutAllowed(true, true)
        }
        dataBinding.all.setOnClickListener {
            filter = "all"
            render()
        }
        dataBinding.movies.setOnClickListener {
            filter = "movie"
            render()
        }
        dataBinding.series.setOnClickListener {
            filter = "tv"
            render()
        }
        dataBinding.unmatched.setOnClickListener {
            filter = "unmatched"
            render()
        }
        dataBinding.back.setOnClickListener { finish() }
        viewModel.state.collectAtStarted(this) {
            current = it
            render()
        }
        dataBinding.all.requestFocus()
    }

    private fun render() {
        val list =
            if (filter ==
                "unmatched"
            ) {
                current.assets.filter { asset -> current.bindings.find { it.assetId == asset.assetId }?.state != "MATCHED" }.map {
                    MetadataCard("asset:${it.assetId}", it.fileName, "识别 / 更正资料")
                }
            } else {
                current.works.filter { filter == "all" || it.mediaKind == filter }.map {
                    MetadataCard(
                        "${it.mediaKind}:${it.tmdbId}",
                        it.title,
                        "${it.releaseDate.take(4)} · ${if (it.mediaKind == "tv") "剧集" else "电影"}",
                        it.poster
                    )
                }
            }
        val hadListFocus = dataBinding.items.hasFocus()
        cards.submitList(list)
        dataBinding.items.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (list.isEmpty() && hadListFocus) dataBinding.all.requestFocus()
        dataBinding.count.text = if (list.isEmpty()) "暂无已入库内容，请从来源文件识别或手动扫描目录。" else "${list.size} 项 · 仅显示已入库内容"
    }
}
