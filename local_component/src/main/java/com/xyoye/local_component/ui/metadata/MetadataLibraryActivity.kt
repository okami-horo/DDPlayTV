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
        val hadListFocus = dataBinding.items.hasFocus()
        var focusedKey = cards.currentList.getOrNull(dataBinding.items.getSelectedPosition())?.key
        if (hadListFocus && filter == "unmatched" && focusedKey?.startsWith("asset:") == true) {
            val id = focusedKey.substringAfter(':').toLongOrNull()
            current.bindings
                .find { binding ->
                    binding.assetId == id &&
                        binding.state == "MATCHED" &&
                        binding.failure == null &&
                        current.works.any { it.mediaKind == binding.mediaKind && it.tmdbId == binding.tmdbId }
                }?.let {
                    filter = "all"
                    focusedKey = "${it.mediaKind}:${it.tmdbId}"
                }
        }
        val list =
            if (filter ==
                "unmatched"
            ) {
                current.assets
                    .filter { asset ->
                        current.bindings.find { it.assetId == asset.assetId }.let {
                            it?.state != "MATCHED" ||
                                it.failure != null
                        }
                    }.map {
                        val binding = current.bindings.find { binding -> binding.assetId == it.assetId }
                        MetadataCard(
                            "asset:${it.assetId}",
                            it.fileName,
                            if (binding?.state ==
                                "IGNORED"
                            ) {
                                "已忽略 · 可重新识别"
                            } else {
                                com.xyoye.common_component.metadata
                                    .metadataFailureText(binding?.failure)
                            }
                        )
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
        cards.submitList(list) {
            if (hadListFocus) {
                val position = list.indexOfFirst { it.key == focusedKey }
                if (position >= 0) dataBinding.items.setSelectedPosition(position)
            }
        }
        dataBinding.items.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (list.isEmpty() && hadListFocus) dataBinding.all.requestFocus()
        dataBinding.count.text = if (list.isEmpty()) "暂无内容。播放视频后自动入库，也可从来源主动扫描目录。" else "${list.size} 项 · 仅显示已入库内容"
    }
}
