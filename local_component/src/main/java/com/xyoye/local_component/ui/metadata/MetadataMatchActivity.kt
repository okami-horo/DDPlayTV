package com.xyoye.local_component.ui.metadata

import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.applyDpadFocusable
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.ActivityMetadataMatchBinding

@Route(path = RouteTable.Local.MetadataMatch)
class MetadataMatchActivity : BaseActivity<MetadataMatchViewModel, ActivityMetadataMatchBinding>() {
    private var initialFocus = true

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataMatchViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_metadata_match

    override fun initView() {
        dataBinding.statusPanel.isFocusable = false
        dataBinding.statusPanel.isFocusableInTouchMode = false
        viewModel.initialize(
            intent.getLongExtra("assetId", 0),
            intent.getIntExtra("libraryId", 0),
            PlaybackFileReference(
                intent.getStringExtra("uniqueKey").orEmpty(),
                intent.getStringExtra("storagePath").orEmpty(),
                protocolReference = intent.getStringExtra("protocolReference")
            )
        )
        viewModel.fileName.observe(this) { dataBinding.fileName.text = it }
        viewModel.status.observe(this) {
            dataBinding.status.text = it
            if (initialFocus && it != "正在读取文件…") {
                initialFocus = false
                if (dataBinding.retry.isEnabled) dataBinding.retry.requestFocus() else dataBinding.back.requestFocus()
            }
        }
        viewModel.advice.observe(this) { dataBinding.advice.text = it }

        fun updateActions() {
            val busy = viewModel.busy.value == true
            val focused = currentFocus
            dataBinding.retry.isEnabled = !busy && viewModel.canRetry.value == true
            dataBinding.refresh.isEnabled = !busy && viewModel.canRefresh.value == true
            dataBinding.retry.applyDpadFocusable(dataBinding.retry.isEnabled)
            dataBinding.refresh.applyDpadFocusable(dataBinding.refresh.isEnabled)
            dataBinding.retry.nextFocusRightId = if (dataBinding.refresh.isEnabled) dataBinding.refresh.id else dataBinding.back.id
            dataBinding.back.nextFocusLeftId =
                when {
                    dataBinding.refresh.isEnabled -> dataBinding.refresh.id
                    dataBinding.retry.isEnabled -> dataBinding.retry.id
                    else -> dataBinding.back.id
                }
            if (focused?.isEnabled == false) dataBinding.back.requestFocus()
        }
        viewModel.canRetry.observe(this) { updateActions() }
        viewModel.canRefresh.observe(this) { updateActions() }
        viewModel.busy.observe(this) { updateActions() }
        dataBinding.retry.setOnClickListener { viewModel.recognizeAgain() }
        dataBinding.refresh.setOnClickListener { viewModel.refresh() }
        dataBinding.back.setOnClickListener { finish() }
        dataBinding.back.requestFocus()
    }
}
