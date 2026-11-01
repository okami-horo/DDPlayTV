package com.xyoye.local_component.ui.metadata

import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import coil.load
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.FragmentCinemaBinding

@Route(path = RouteTable.Local.CinemaFragment)
class CinemaFragment :
    BaseFragment<MetadataLibraryViewModel, FragmentCinemaBinding>(),
    DpadFocusOwner {
    private var current = MetadataLibraryState()
    private var heroKey: String? = null
    private var heroWork: com.xyoye.data_component.entity.MetadataWork? = null
    private var displayedHistory = emptyList<MetadataHistory>()
    private var restoredKey: String? = null
    private var restoredHistoryId: Int? = null
    private val history =
        MetadataCardAdapter(continueWatching = true, click = { card ->
            displayedHistory.find { it.key == card.key }?.let { entry ->
                restoredKey = entry.key
                restoredHistoryId = entry.history.id
                if (entry.tmdbId != null) openCard(card, entry.history.id) else viewModel.play(entry.history)
            }
        })

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        restoredKey = savedInstanceState?.getString("historyKey")
        restoredHistoryId = savedInstanceState?.getInt("historyId")
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        bindingOrNull?.continueWatching?.takeIf { it.hasFocus() }?.let { list ->
            displayedHistory.getOrNull(list.getSelectedPosition())?.let {
                outState.putString("historyKey", it.key)
                outState.putInt("historyId", it.history.id)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataLibraryViewModel::class.java)

    override fun getLayoutId() = R.layout.fragment_cinema

    override fun initView() {
        dataBinding.cinemaScroll.isFocusable = false
        androidx.lifecycle
            .ViewModelProvider(
                this
            )[com.xyoye.local_component.ui.fragment.media.MediaViewModel::class.java]
            .initLocalStorage()
        dataBinding.continueWatching.apply {
            layoutManager = horizontal()
            addItemDecoration(
                com.xyoye.common_component.utils.view
                    .ItemDecorationSpace((4 * resources.displayMetrics.density).toInt(), 0)
            )
            adapter = history
        }
        dataBinding.filterAll.setOnClickListener {
            parentFragmentManager.setFragmentResult(RouteTable.App.CinemaNavigation, androidx.core.os.bundleOf("page" to "library"))
        }
        dataBinding.heroPlay.setOnClickListener {
            val entry = current.history.firstOrNull()
            if (entry != null) {
                viewModel.play(entry)
            } else {
                if (heroWork != null) {
                    heroWork?.let { openCard(MetadataCard("${it.mediaKind}:${it.tmdbId}", it.title)) }
                } else {
                    parentFragmentManager.setFragmentResult(RouteTable.App.CinemaNavigation, androidx.core.os.bundleOf("page" to "library"))
                }
            }
        }
        viewModel.state.collectAtStarted(viewLifecycleOwner) {
            current = it
            render()
        }
        viewModel.playEvents.collectAtStarted(viewLifecycleOwner) { result ->
            when (result) {
                is PlaybackSubmissionResult.Accepted ->
                    ARouter
                        .getInstance()
                        .build(
                            RouteTable.Player.Player
                        ).withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                        .navigation(requireActivity())
                is PlaybackSubmissionResult.Rejected -> ToastCenter.showError(result.failure.userMessage())
            }
        }
    }

    private fun render() {
        val binding = bindingOrNull ?: return
        if (!current.loaded) {
            binding.heroTitle.text = "正在读取观看足迹…"
            binding.heroPlay.isEnabled = false
            binding.heroPlay.alpha = 0.45f
            binding.empty.text = "正在读取观看足迹…"
            binding.empty.isVisible = true
            return
        }
        binding.heroPlay.isEnabled = true
        binding.heroPlay.alpha = 1f
        val projected = projectMetadataHistory(current)
        val latest = projected.firstOrNull()
        val featured =
            if (latest != null) {
                latest.work
            } else {
                current.works.firstOrNull { "${it.mediaKind}:${it.tmdbId}" == heroKey } ?: current.works.firstOrNull()
            }
        val key = featured?.let { "${it.mediaKind}:${it.tmdbId}" }
        if (heroWork != featured || binding.heroOverview.text.isEmpty()) {
            heroKey = key
            heroWork = featured
            binding.backdrop.load(featured?.backdrop)
            binding.heroOverview.text = featured?.overview?.ifBlank { "保留每个文件，发现每部作品。" } ?: "连接本地与网络来源，开始你的大屏时光。"
        }
        binding.heroTitle.text = featured?.title ?: latest?.history?.videoName ?: "每一部，都值得好好看"
        binding.heroLabel.text = if (latest != null) "DDPLAYTV · 继续观看" else "DDPLAYTV · 你的私人放映室"
        binding.heroMeta.text =
            featured?.let {
                listOfNotNull(
                    it.releaseDate.take(4).ifBlank { null },
                    if (it.mediaKind == "tv") "剧集 / 番剧" else "电影",
                    latest?.takeIf { entry -> entry.kind == "tv" }?.episodeLabel
                ).joinToString("  ·  ")
            } ?: "本地与远程媒体"
        binding.heroPlay.text =
            when {
                latest != null -> "继续播放"
                featured != null -> "查看作品"
                else -> "浏览媒体库"
            }
        val recent = projected.distinctBy { it.key }.take(8)
        val hadListFocus = binding.continueWatching.hasFocus()
        val oldPosition = binding.continueWatching.getSelectedPosition()
        val oldKey = history.currentList.getOrNull(oldPosition)?.key
        val oldEntry = displayedHistory.find { it.key == oldKey }
        val focusedKey = restoredKey ?: oldEntry?.key
        val historyId = restoredHistoryId ?: oldEntry?.history?.id
        val migratedKey = projected.find { it.history.id == historyId }?.key
        val restore = restoredKey != null
        if (hadListFocus) {
            restoredKey = focusedKey
            restoredHistoryId = historyId
        }
        history.submitList(recent.map { it.card() }) {
            displayedHistory = recent
            if ((hadListFocus || restore) && bindingOrNull === binding) {
                val position =
                    recent
                        .indexOfFirst { it.key == focusedKey || it.key == migratedKey }
                        .takeIf { it >= 0 } ?: oldPosition.coerceIn(0, (recent.size - 1).coerceAtLeast(0))
                if (recent.isNotEmpty()) {
                    // Apply the stable-key position after RecyclerView consumes the move updates.
                    binding.continueWatching.doOnLayout {
                        if (bindingOrNull !== binding) return@doOnLayout
                        binding.continueWatching.setSelectedPosition(position)
                        if (!binding.continueWatching.hasFocus()) binding.continueWatching.requestFocus()
                        restoredKey = null
                        restoredHistoryId = null
                    }
                } else {
                    binding.heroPlay.requestFocus()
                    restoredKey = null
                    restoredHistoryId = null
                }
            }
        }
        binding.continueWatching.isVisible = recent.isNotEmpty()
        binding.continueTitle.isVisible = true
        binding.empty.isVisible = recent.isEmpty()
        binding.empty.text = "从媒体库添加来源，开始你的大屏时光。"
        listOf(binding.heroPlay, binding.filterAll).forEach {
            it.nextFocusDownId = if (recent.isNotEmpty()) binding.continueWatching.id else it.id
        }
    }

    private fun openCard(
        card: MetadataCard,
        historyId: Int? = null
    ) {
        if (card.key.startsWith("asset:")) {
            correctCard(card)
            return
        }
        ARouter
            .getInstance()
            .build(
                RouteTable.Local.MetadataDetail
            ).withString("kind", card.key.substringBefore(':'))
            .withLong("tmdbId", card.key.substringAfter(':').toLong())
            .withInt("historyId", historyId ?: 0)
            .navigation()
    }

    private fun correctCard(card: MetadataCard) {
        if (!card.key.startsWith("asset:")) return
        ARouter
            .getInstance()
            .build(RouteTable.Local.MetadataMatch)
            .withLong("assetId", card.key.substringAfter(':').toLong())
            .navigation()
    }

    override fun requestDefaultDpadFocus(): Boolean {
        val binding = bindingOrNull ?: return false
        return (if (binding.heroPlay.isVisible) binding.heroPlay else binding.filterAll).requestFocus()
    }

    override fun onDestroyView() {
        bindingOrNull?.continueWatching?.adapter = null
        super.onDestroyView()
    }
}
