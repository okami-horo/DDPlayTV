package com.xyoye.local_component.ui.metadata

import androidx.lifecycle.lifecycleScope
import coil.load
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.ActivityMetadataDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Route(path = RouteTable.Local.MetadataDetail)
class MetadataDetailActivity : BaseActivity<MetadataLibraryViewModel, ActivityMetadataDetailBinding>() {
    private val kind get() = intent.getStringExtra("kind") ?: "movie"
    private val tmdbId get() = intent.getLongExtra("tmdbId", 0)
    private var current = MetadataLibraryState()
    private var selectedSeason: Int? = null
    private var groups = emptyMap<String, List<MediaAsset>>()
    private var rowsJob: Job? = null
    private val files = MetadataCardAdapter(click = { choose(it.key, false) }, longClick = { choose(it.key, true) })
    private val seasons =
        MetadataCardAdapter(compact = true, click = {
            selectedSeason = it.key.toInt()
            render()
        })

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        selectedSeason = savedInstanceState?.getInt("season", -1)?.takeIf { it >= 0 }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        selectedSeason?.let { outState.putInt("season", it) }
        super.onSaveInstanceState(outState)
    }

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataLibraryViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_detail

    override fun initView() {
        dataBinding.files.apply {
            layoutManager = vertical()
            adapter = files
            setFocusOutAllowed(true, true)
        }
        dataBinding.seasons.apply {
            layoutManager = horizontal()
            adapter = seasons
        }
        dataBinding.back.setOnClickListener { finish() }
        dataBinding.correct.setOnClickListener {
            val all = groups.values.flatten().distinctBy { it.assetId }
            chooseFiles(all, true)
        }
        viewModel.state.collectAtStarted(this) {
            current = it
            render()
        }
        viewModel.playEvents.collectAtStarted(this) { result ->
            when (result) {
                is PlaybackSubmissionResult.Accepted ->
                    ARouter
                        .getInstance()
                        .build(
                            RouteTable.Player.Player
                        ).withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                        .navigation(this@MetadataDetailActivity)
                is PlaybackSubmissionResult.Rejected -> ToastCenter.showError(result.failure.userMessage())
            }
        }
        dataBinding.back.requestFocus()
    }

    private fun render() {
        val work = current.works.find { it.mediaKind == kind && it.tmdbId == tmdbId }
        dataBinding.title.text = work?.title ?: "作品资料暂不可用"
        dataBinding.overview.text = work?.let { "${it.releaseDate.take(4)} · ${it.genres}\n${it.overview}" }.orEmpty()
        dataBinding.backdrop.load(work?.backdrop)
        dataBinding.poster.load(work?.poster) {
            placeholder(R.drawable.cinema_media_placeholder)
            fallback(R.drawable.cinema_media_placeholder)
            error(R.drawable.cinema_media_placeholder)
        }
        val bound = current.bindings.filter { it.mediaKind == kind && it.tmdbId == tmdbId }
        val assets = current.assets.filter { asset -> bound.any { it.assetId == asset.assetId } }
        val links = current.episodes.filter { link -> assets.any { it.assetId == link.assetId } }
        val seasonNumbers = links.map { it.seasonNumber }.distinct().sorted()
        if (selectedSeason !in seasonNumbers) selectedSeason = seasonNumbers.firstOrNull()
        seasons.submitList(seasonNumbers.map { MetadataCard(it.toString(), if (it == 0) "特别篇" else "第 $it 季") })
        dataBinding.seasons.visibility = if (seasonNumbers.size > 1) android.view.View.VISIBLE else android.view.View.GONE
        val grouped = linkedMapOf<String, List<MediaAsset>>()
        if (kind == "movie") {
            if (assets.isNotEmpty()) grouped["movie"] = assets
        } else {
            links.filter { it.seasonNumber == selectedSeason }.map { it.episodeNumber }.distinct().sorted().forEach { number ->
                grouped["episode:$number"] =
                    assets.filter { asset ->
                        links.any {
                            it.assetId == asset.assetId &&
                                it.seasonNumber == selectedSeason &&
                                it.episodeNumber == number
                        }
                    }
            }
            assets
                .filter { asset ->
                    links.none { it.assetId == asset.assetId } ||
                        bound.find { it.assetId == asset.assetId }?.failure == "FILE_CHANGED"
                }.forEach {
                    grouped["asset:${it.assetId}"] = listOf(it)
                }
        }
        groups = grouped
        dataBinding.hint.text = if (assets.isEmpty()) "没有当前来源文件。请返回来源确认文件与访问权限。" else "选择文件播放 · 长按确认键更正资料 · 合并集共享同一文件进度"
        rowsJob?.cancel()
        val season = selectedSeason
        rowsJob =
            lifecycleScope.launch {
                val cards =
                    withContext(Dispatchers.IO) {
                        val episodeData = season?.let { viewModel.repository.dao.episodes(tmdbId, it) }.orEmpty()
                        val sources =
                            assets
                                .map {
                                    it.libraryId
                                }.distinct()
                                .associateWith {
                                    viewModel.repository.database
                                        .getMediaLibraryDao()
                                        .getById(it)
                                        ?.displayName
                                        .orEmpty()
                                }
                        grouped.map { (key, copies) ->
                            val episode =
                                key.substringAfter("episode:", "").toIntOrNull()?.let { n ->
                                    episodeData.find {
                                        it.episodeNumber ==
                                            n
                                    }
                                }
                            val label =
                                if (key ==
                                    "movie"
                                ) {
                                    "播放电影"
                                } else if (key.startsWith("asset:")) {
                                    "待确认 · ${copies.first().fileName}"
                                } else {
                                    "第 ${key.substringAfter(':')} 集 · ${episode?.title.orEmpty()}"
                                }
                            val merged = copies.any { copy -> links.count { it.assetId == copy.assetId } > 1 }
                            MetadataCard(
                                key,
                                label,
                                "${copies.size} 个文件${if (merged) " · 合并集" else ""} · ${copies.map {
                                    sources[it.libraryId]
                                }.distinct().joinToString(
                                    " / "
                                )}\n${copies.firstOrNull()?.fileName.orEmpty()}",
                                episode?.still ?: work?.poster
                            )
                        }
                    }
                val hadListFocus = dataBinding.files.hasFocus()
                files.submitList(cards)
                dataBinding.files.visibility = if (cards.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                if (cards.isEmpty() && hadListFocus) dataBinding.back.requestFocus()
            }
    }

    private fun choose(
        key: String,
        correct: Boolean
    ) {
        chooseFiles(groups[key].orEmpty(), correct)
    }

    private fun chooseFiles(
        copies: List<MediaAsset>,
        correct: Boolean
    ) {
        if (copies.isEmpty()) {
            ToastCenter.showWarning("没有可选择的文件")
            return
        }
        if (copies.size == 1) {
            open(copies.single(), correct)
            return
        }
        BottomActionDialog(
            this,
            copies.map {
                SheetActionBean(
                    it.assetId,
                    "来源 ${it.libraryId} · ${it.fileName}${if (!it.available) "（暂不可用）" else ""}",
                    R.drawable.ic_local_file
                )
            },
            "选择实际文件版本"
        ) { selected ->
            copies.find { it.assetId == selected.actionId }?.let { open(it, correct) }
            true
        }.show()
    }

    private fun open(
        asset: MediaAsset,
        correct: Boolean
    ) {
        if (correct) {
            ARouter
                .getInstance()
                .build(RouteTable.Local.MetadataMatch)
                .withLong("assetId", asset.assetId)
                .navigation()
        } else if (!asset.available) {
            ToastCenter.showWarning("文件暂不可用，请检查来源并重新扫描；也可更正资料或返回文件浏览。")
        } else {
            viewModel.play(asset)
        }
    }
}
