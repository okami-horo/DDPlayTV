package com.xyoye.local_component.ui.metadata

import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.ActivityMetadataMatchBinding

@Route(path = RouteTable.Local.MetadataMatch)
class MetadataMatchActivity : BaseActivity<MetadataMatchViewModel, ActivityMetadataMatchBinding>() {
    private var queryInitialized = false

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataMatchViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_match

    override fun initView() {
        val adapter = MetadataCardAdapter(click = viewModel::select)
        dataBinding.items.apply {
            layoutManager = vertical()
            this.adapter = adapter
            setFocusOutAllowed(true, true)
        }
        viewModel.initialize(
            intent.getLongExtra("assetId", 0),
            intent.getIntExtra("libraryId", 0),
            PlaybackFileReference(
                intent.getStringExtra("uniqueKey").orEmpty(),
                intent.getStringExtra("storagePath").orEmpty(),
                protocolReference = intent.getStringExtra("protocolReference")
            )
        )
        viewModel.cards.observe(this) {
            val hadListFocus = dataBinding.items.hasFocus()
            adapter.submitList(it)
            dataBinding.items.isVisible = it.isNotEmpty()
            if (it.isEmpty() && hadListFocus) {
                when {
                    dataBinding.confirm.isVisible -> dataBinding.confirm.requestFocus()
                    dataBinding.search.isShown -> dataBinding.search.requestFocus()
                    else -> dataBinding.back.requestFocus()
                }
            }
        }
        viewModel.status.observe(this) {
            dataBinding.status.text = it
            if (viewModel.ticket != null) {
                dataBinding.fileName.text = viewModel.ticket?.asset?.fileName
                if (!queryInitialized || !dataBinding.query.hasFocus()) dataBinding.query.setText(viewModel.query)
                queryInitialized = true
            }
            dataBinding.kind.text = if (viewModel.kind == "movie") "电影" else "剧集 / 番剧"
            dataBinding.refresh.isEnabled = viewModel.ticket?.binding?.tmdbId != null
            dataBinding.ignore.isEnabled = viewModel.ticket != null
        }
        viewModel.stage.observe(this) {
            dataBinding.searchRow.isVisible = it == "search"
            dataBinding.rangeRow.isVisible = it == "episodes" && viewModel.episodes.isNotEmpty()
            dataBinding.confirm.isVisible = it == "confirm" || it == "batch" || it == "episodes" && viewModel.episodes.isNotEmpty()
            dataBinding.previewDirectory.isVisible = it == "episodes" &&
                viewModel.episodes.isNotEmpty() &&
                viewModel.ticket
                    ?.asset
                    ?.parentPath
                    ?.isNotBlank() == true
            dataBinding.first.text = "起始集：${viewModel.episodes.getOrNull(viewModel.firstIndex)?.episodeNumber ?: "—"}"
            dataBinding.last.text = "结束集：${viewModel.episodes.getOrNull(viewModel.lastIndex)?.episodeNumber ?: "—"}"
            if (hasWindowFocus() && (currentFocus == null || currentFocus?.isShown == false)) {
                if (dataBinding.confirm.isVisible) dataBinding.confirm.requestFocus() else dataBinding.items.requestFocus()
            }
        }
        viewModel.saved.observe(this) {
            if (it) {
                setResult(RESULT_OK)
                finish()
            }
        }
        dataBinding.kind.setOnClickListener {
            viewModel.kind = if (viewModel.kind == "movie") "tv" else "movie"
            dataBinding.kind.text = if (viewModel.kind == "movie") "电影" else "剧集 / 番剧"
        }
        dataBinding.search.setOnClickListener { viewModel.search(dataBinding.query.text.toString(), viewModel.kind) }
        dataBinding.confirm.setOnClickListener { viewModel.confirm() }
        dataBinding.previewDirectory.setOnClickListener { viewModel.previewDirectory() }
        dataBinding.ignore.setOnClickListener { viewModel.ignore() }
        dataBinding.refresh.setOnClickListener { viewModel.refresh() }
        dataBinding.back.setOnClickListener { if (!viewModel.back()) finish() }
        listOf(dataBinding.first to true, dataBinding.last to false).forEach { (button, first) ->
            button.setOnClickListener { viewModel.step(first, 1) }
            button.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_DOWN) viewModel.step(first, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    true
                } else {
                    false
                }
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.back()) finish()
                }
            }
        )
        dataBinding.search.requestFocus()
    }
}
