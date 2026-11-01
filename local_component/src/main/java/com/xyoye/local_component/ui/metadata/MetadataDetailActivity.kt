package com.xyoye.local_component.ui.metadata

import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.rubensousa.dpadrecyclerview.ParentAlignment
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.focus.applyDpadFocusable
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
    private var allAssets = emptyList<MediaAsset>()
    private var projected = emptyList<MetadataHistory>()
    private var recent: MetadataHistory? = null
    private var startCopies = emptyList<MediaAsset>()
    private var sources = emptyMap<Int, String>()
    private var rowsJob: Job? = null
    private var initialized = false
    private var savedFocusId = View.NO_ID
    private var savedEpisodeKey: String? = null
    private var savedListState: Parcelable? = null
    private var renderedSeason: Int? = null
    private var playbackFailure: String? = null
    private var overviewTitle = "作品简介"
    private var overviewText = ""
    private val files = MetadataEpisodeAdapter { chooseFiles(groups[it].orEmpty(), false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedSeason = savedInstanceState?.getInt("season", -1)?.takeIf { it >= 0 }
        savedFocusId = savedInstanceState?.getInt("focusId", View.NO_ID) ?: View.NO_ID
        savedEpisodeKey = savedInstanceState?.getString("episodeKey")
        savedListState = savedInstanceState?.getParcelable("listState")
        playbackFailure = savedInstanceState?.getString("playbackFailure")
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedSeason?.let { outState.putInt("season", it) }
        val list = dataBinding.files
        outState.putInt(
            "focusId",
            if (list.hasFocus()) {
                list.id
            } else if (dataBinding.seasons.hasFocus()) {
                R.id.seasons
            } else {
                currentFocus?.id
                    ?: View.NO_ID
            }
        )
        files.currentList.getOrNull(list.getSelectedPosition())?.let { outState.putString("episodeKey", it.card.key) }
        outState.putParcelable("listState", list.layoutManager?.onSaveInstanceState())
        outState.putString("playbackFailure", playbackFailure)
        super.onSaveInstanceState(outState)
    }

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataLibraryViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_detail

    override fun initView() {
        (dataBinding.root as android.view.ViewGroup).descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dataBinding.files.apply {
            layoutManager = horizontal()
            setParentAlignment(ParentAlignment(fraction = 0.8f))
            adapter = files
            setFocusOutAllowed(false, false)
            setFocusOutSideAllowed(true, true)
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val radius = 22 * resources.displayMetrics.density
            dataBinding.backdrop.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
            )
        }
        dataBinding.resume.setOnClickListener {
            playbackFailure = null
            recent?.let { viewModel.play(it.history) } ?: chooseFiles(startCopies, false)
        }
        dataBinding.correct.setOnClickListener { chooseFiles(allAssets, true) }
        dataBinding.browse.setOnClickListener { ARouter.getInstance().build(RouteTable.Local.MetadataLibrary).navigation() }
        dataBinding.synopsis.setOnClickListener {
            AlertDialog
                .Builder(this)
                .setTitle(overviewTitle)
                .setMessage(overviewText)
                .show()
        }
        viewModel.state.collectAtStarted(this) {
            current = it
            if (it.loaded) render()
        }
        viewModel.playEvents.collectAtStarted(this) { result ->
            when (result) {
                is PlaybackSubmissionResult.Accepted ->
                    ARouter
                        .getInstance()
                        .build(RouteTable.Player.Player)
                        .withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                        .navigation(this@MetadataDetailActivity, PLAYBACK_REQUEST)
                is PlaybackSubmissionResult.Rejected -> {
                    playbackFailure = result.failure.userMessage()
                    dataBinding.hint.text = result.failure.userMessage()
                    dataBinding.hint.isVisible = true
                    ToastCenter.showError(result.failure.userMessage())
                }
            }
        }
    }

    @Deprecated("Activity result bridge required by the existing ARouter entry")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PLAYBACK_REQUEST) {
            data?.getStringExtra(PlaybackNavigation.FAILURE_MESSAGE)?.let {
                playbackFailure = it
                dataBinding.hint.text = it
                dataBinding.hint.isVisible = true
            }
        }
    }

    private fun render() {
        rowsJob?.cancel()
        val state = current
        val knownWork = state.works.find { it.mediaKind == kind && it.tmdbId == tmdbId }
        val bound = state.bindings.filter { it.mediaKind == kind && it.tmdbId == tmdbId }
        val assets = state.assets.filter { asset -> bound.any { it.assetId == asset.assetId } }
        val playableAssets =
            assets.filter { asset ->
                asset.available &&
                    bound.none { it.assetId == asset.assetId && it.failure == "FILE_CHANGED" }
            }
        val links =
            state.episodes.filter { link ->
                assets.any { it.assetId == link.assetId } &&
                    bound.none { it.assetId == link.assetId && it.failure == "FILE_CHANGED" }
            }
        val history = projectMetadataHistory(state)
        val historyHint = intent.getIntExtra("historyId", 0)
        val latest =
            history
                .filter { it.kind == kind && it.tmdbId == tmdbId }
                .maxWithOrNull(compareBy<MetadataHistory> { it.history.playTime }.thenBy { it.history.id == historyHint })
        val seasonNumbers = links.map { it.seasonNumber }.distinct().sorted()
        val playableSeasons =
            links
                .filter { link ->
                    assets.any { it.assetId == link.assetId && it.available }
                }.map { it.seasonNumber }
                .distinct()
        selectedSeason = initialDetailSeason(seasonNumbers, selectedSeason, latest)
        if (!initialized && latest == null && selectedSeason !in playableSeasons && playableSeasons.isNotEmpty()) {
            selectedSeason = initialDetailSeason(playableSeasons, null, null)
        }
        val season = selectedSeason
        val grouped = linkedMapOf<String, List<MediaAsset>>()
        if (kind == "movie") {
            if (assets.isNotEmpty()) grouped["movie"] = assets
        } else {
            links.filter { it.seasonNumber == season }.map { it.episodeNumber }.distinct().sorted().forEach { number ->
                grouped["episode:$season:$number"] =
                    assets.filter { asset ->
                        links.any {
                            it.assetId == asset.assetId &&
                                it.seasonNumber == season &&
                                it.episodeNumber == number
                        }
                    }
            }
            assets.filter { asset -> links.none { it.assetId == asset.assetId } }.forEach { grouped["asset:${it.assetId}"] = listOf(it) }
        }
        val firstLink =
            links
                .filter { link -> playableAssets.any { it.assetId == link.assetId } }
                .sortedWith(
                    compareBy<com.xyoye.data_component.entity.MediaBindingEpisode> {
                        if (it.seasonNumber ==
                            0
                        ) {
                            Int.MAX_VALUE
                        } else {
                            it.seasonNumber
                        }
                    }.thenBy { it.episodeNumber }
                ).firstOrNull()
        val firstCopies =
            if (kind == "movie" || firstLink == null) {
                playableAssets.take(1)
            } else {
                assets.filter { asset ->
                    asset.available &&
                        links.any {
                            it.assetId == asset.assetId &&
                                it.seasonNumber == firstLink.seasonNumber &&
                                it.episodeNumber == firstLink.episodeNumber
                        }
                }
            }
        rowsJob =
            lifecycleScope.launch {
                val (work, episodeData, sourceNames) =
                    withContext(Dispatchers.IO) {
                        val episodes = season?.let { viewModel.repository.dao.episodes(tmdbId, it) }.orEmpty()
                        val names =
                            assets.map { it.libraryId }.distinct().associateWith {
                                viewModel.repository.database
                                    .getMediaLibraryDao()
                                    .getById(it)
                                    ?.displayName
                                    .orEmpty()
                                    .ifBlank { "来源 $it" }
                            }
                        Triple(knownWork ?: viewModel.repository.dao.work(kind, tmdbId), episodes, names)
                    }
                groups = grouped
                allAssets = assets
                projected = history
                recent = latest
                startCopies = firstCopies
                sources = sourceNames
                overviewTitle = work?.title ?: "作品简介"
                overviewText = work?.overview.orEmpty()
                dataBinding.title.text = work?.title ?: "作品资料暂不可用"
                dataBinding.poster.isVisible = work != null
                dataBinding.workMeta.isVisible = work != null
                dataBinding.workMeta.text =
                    listOf(work?.releaseDate?.take(4).orEmpty(), work?.genres.orEmpty(), if (kind == "tv") "剧集" else "电影")
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                dataBinding.overview.text = work?.overview?.ifBlank { "暂无作品简介" } ?: "可以从媒体库检查来源与作品资料。"
                dataBinding.backdrop.load(work?.backdrop ?: work?.poster)
                dataBinding.poster.load(work?.poster) {
                    placeholder(R.drawable.cinema_media_placeholder)
                    fallback(R.drawable.cinema_media_placeholder)
                    error(R.drawable.cinema_media_placeholder)
                }
                dataBinding.synopsis.isVisible = !work?.overview.isNullOrBlank()
                dataBinding.synopsis.applyDpadFocusable(dataBinding.synopsis.isVisible)
                dataBinding.correct.applyDpadFocusable(assets.isNotEmpty())
                dataBinding.correct.isVisible = assets.isNotEmpty()
                dataBinding.correct.isEnabled = assets.isNotEmpty()
                val canResume = latest?.asset?.available ?: firstCopies.isNotEmpty()
                dataBinding.resume.isEnabled = canResume
                dataBinding.resume.applyDpadFocusable(canResume)
                dataBinding.resume.alpha = if (canResume) 1f else 0.45f
                dataBinding.resume.text =
                    when {
                        latest != null && kind == "tv" && latest.episodes.size == 1 -> "继续播放 · 第 ${latest.episodes.first().episodeNumber} 集"
                        latest != null -> "继续播放"
                        else -> "开始观看"
                    }
                dataBinding.resumeNote.text =
                    when {
                        latest?.asset?.available == false -> "上次文件暂不可用，请检查来源；可以选择其他版本。"
                        latest != null -> "${latest.subtitle} · ${sources[latest.asset?.libraryId].orEmpty()}"
                        firstLink != null -> "${seasonLabel(
                            firstLink.seasonNumber
                        )} · 第 ${firstLink.episodeNumber} 集 · ${sources[firstCopies.firstOrNull()?.libraryId].orEmpty()}"
                        firstCopies.isNotEmpty() -> sources[firstCopies.first().libraryId].orEmpty()
                        else -> "暂无可播放文件"
                    }
                dataBinding.sourceLabel.text =
                    listOfNotNull(
                        sources[(latest?.asset ?: firstCopies.firstOrNull())?.libraryId],
                        season?.let(::seasonLabel)
                    ).joinToString(" · ")
                dataBinding.seasonScroll.isVisible = seasonNumbers.size > 1
                dataBinding.seasonLabel.isVisible = seasonNumbers.size == 1
                dataBinding.seasonLabel.text = season?.let(::seasonLabel).orEmpty()
                if (dataBinding.seasons.children
                        .map { it.tag }
                        .toList() != seasonNumbers
                ) {
                    dataBinding.seasons.removeAllViews()
                    seasonNumbers.forEach { number ->
                        val button = layoutInflater.inflate(R.layout.item_metadata_season, dataBinding.seasons, false) as Button
                        button.id = View.generateViewId()
                        button.tag = number
                        button.text = seasonLabel(number)
                        button.setOnClickListener {
                            selectedSeason = number
                            render()
                        }
                        dataBinding.seasons.addView(button)
                    }
                }
                dataBinding.seasons.children.forEach { it.isSelected = it.tag == season }
                dataBinding.episodesTitle.text = if (kind == "movie") "影片文件" else "剧集"
                dataBinding.episodeCount.text =
                    if (kind == "movie") "${assets.size} 个版本" else "${grouped.keys.count { it.startsWith("episode:") }} 集"
                dataBinding.hint.text = playbackFailure ?: if (links.groupBy { it.assetId }.any { it.value.size > 1 }) "合并集共享同一文件进度" else ""
                dataBinding.hint.isVisible = dataBinding.hint.text.isNotEmpty()
                val primary =
                    if (canResume) {
                        dataBinding.resume
                    } else if (assets.isNotEmpty()) {
                        dataBinding.correct
                    } else {
                        dataBinding.browse
                    }
                val up = if (seasonNumbers.size > 1) dataBinding.seasons.children.first { it.tag == season } else primary
                files.upTarget = up.id
                // Direction IDs can change even when card content stays equal in DiffUtil.
                dataBinding.files.children.forEach { it.nextFocusUpId = up.id }
                val cards =
                    grouped.map { (key, copies) ->
                        val number = key.takeIf { it.startsWith("episode:") }?.substringAfterLast(':')?.toIntOrNull()
                        val episode = episodeData.find { it.episodeNumber == number }
                        val entry = history.firstOrNull { item -> copies.any { it.assetId == item.asset?.assetId } }
                        val merged = copies.any { copy -> links.count { it.assetId == copy.assetId } > 1 }
                        val label =
                            when {
                                key == "movie" -> work?.title ?: "播放电影"
                                number != null -> "%02d · %s".format(number, episode?.title?.ifBlank { "第 $number 集" } ?: "第 $number 集")
                                else -> "季集待确认 · ${copies.first().fileName}"
                            }
                        val description =
                            listOfNotNull(
                                if (copies.none { it.available }) "文件暂不可用" else null,
                                entry?.let { historyProgress(it.history, if (merged || kind == "movie") "文件" else "本集") },
                                if (copies.size > 1) "${copies.size} 个版本" else null,
                                if (merged) "合并集" else null
                            ).joinToString(" · ").ifBlank { sources[copies.first().libraryId].orEmpty() }
                        MetadataEpisodeCard(
                            MetadataCard(
                                key,
                                label,
                                description,
                                episode?.still ?: work?.poster?.takeIf {
                                    kind == "movie"
                                },
                                entry?.let { historyPercent(it.history) }
                            ),
                            number?.let { "%02d".format(it) } ?: "▶",
                            copies.any { it.assetId == latest?.asset?.assetId }
                        )
                    }
                val hadListFocus = dataBinding.files.hasFocus()
                val focusedKey =
                    files.currentList
                        .getOrNull(dataBinding.files.getSelectedPosition())
                        ?.card
                        ?.key
                val previousPosition = dataBinding.files.getSelectedPosition()
                val seasonChanged = renderedSeason != season
                renderedSeason = season
                dataBinding.files.isVisible = cards.isNotEmpty()
                dataBinding.emptyPanel.isVisible = cards.isEmpty()
                dataBinding.detailStatus.text = if (work == null) "作品资料暂不可用，可从媒体库检查来源。" else "还没有发现这部作品的文件。"
                dataBinding.browse.isVisible = cards.isEmpty()
                dataBinding.seasons.children.forEach {
                    it.nextFocusUpId = primary.id
                    it.nextFocusDownId = if (cards.isNotEmpty()) R.id.files else R.id.browse
                }
                dataBinding.browse.nextFocusUpId = if (dataBinding.synopsis.isVisible) R.id.synopsis else R.id.browse
                listOf(dataBinding.resume, dataBinding.synopsis, dataBinding.correct).forEach { action ->
                    action.nextFocusDownId = if (cards.isNotEmpty()) R.id.files else R.id.browse
                    action.setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && cards.isNotEmpty()) {
                            dataBinding.files.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                }
                dataBinding.resume.nextFocusRightId = if (dataBinding.synopsis.isVisible) R.id.synopsis else R.id.correct
                dataBinding.correct.nextFocusLeftId = if (dataBinding.synopsis.isVisible) R.id.synopsis else primary.id
                dataBinding.synopsis.nextFocusLeftId = primary.id
                files.submitList(cards) {
                    if (isDestroyed || isFinishing) return@submitList
                    val key =
                        if (!initialized) {
                            savedEpisodeKey
                        } else if (!seasonChanged) {
                            focusedKey
                        } else {
                            null
                        }
                    val selected =
                        cards.indexOfFirst { it.card.key == key }.takeIf { it >= 0 }
                            ?: cards.indexOfFirst { it.lastWatched }.takeIf { it >= 0 && (!initialized || seasonChanged) }
                            ?: if (hadListFocus && !seasonChanged) previousPosition.coerceIn(0, (cards.size - 1).coerceAtLeast(0)) else 0
                    if (cards.isNotEmpty()) dataBinding.files.setSelectedPosition(selected)
                    if (!initialized) {
                        savedListState?.let { dataBinding.files.layoutManager?.onRestoreInstanceState(it) }
                        savedListState = null
                        val target =
                            (if (savedFocusId == R.id.seasons) up else findViewById<View>(savedFocusId))
                                ?.takeIf { it.isShown && it.isEnabled && it.isFocusable }
                        initialized = true
                        dataBinding.root.post {
                            (dataBinding.root as android.view.ViewGroup).descendantFocusability =
                                android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                            if (!isDestroyed && !dataBinding.root.isInTouchMode) {
                                when {
                                    target != null -> target.requestFocus()
                                    canResume -> dataBinding.resume.requestFocus()
                                    cards.any { card -> grouped[card.card.key].orEmpty().any { it.available } } -> {
                                        dataBinding.files.setSelectedPosition(
                                            cards.indexOfFirst { card ->
                                                grouped[card.card.key].orEmpty().any { it.available }
                                            }
                                        )
                                        dataBinding.files.requestFocus()
                                    }
                                    assets.isNotEmpty() -> dataBinding.correct.requestFocus()
                                    else -> dataBinding.browse.requestFocus()
                                }
                            }
                        }
                    } else if (hadListFocus && cards.isEmpty() || currentFocus?.isShown != true || currentFocus?.isFocusable != true) {
                        primary.requestFocus()
                    }
                }
            }
    }

    private fun chooseFiles(
        copies: List<MediaAsset>,
        correct: Boolean
    ) {
        if (copies.isEmpty()) return
        if (copies.size == 1) {
            open(copies.single(), correct)
            return
        }
        BottomActionDialog(
            this,
            copies.map { asset ->
                val history = projected.find { it.asset?.assetId == asset.assetId }?.history
                SheetActionBean(
                    asset.assetId,
                    "${sources[asset.libraryId] ?: "来源 ${asset.libraryId}"}${if (!asset.available) "（暂不可用）" else ""}",
                    R.drawable.ic_local_file,
                    "${asset.fileName}\n${history?.let { historyProgress(it) } ?: "尚未观看"}"
                )
            },
            if (correct) "选择要管理的文件" else "选择实际文件版本"
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
            dataBinding.hint.text = "文件暂不可用，请检查来源或选择其他版本。"
            dataBinding.hint.isVisible = true
            ToastCenter.showWarning(dataBinding.hint.text.toString())
        } else {
            playbackFailure = null
            viewModel.play(asset)
        }
    }

    private companion object {
        const val PLAYBACK_REQUEST = 17291
    }
}
