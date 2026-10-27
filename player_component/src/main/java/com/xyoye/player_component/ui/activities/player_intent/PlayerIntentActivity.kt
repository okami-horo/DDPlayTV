package com.xyoye.player_component.ui.activities.player_intent

import android.net.Uri
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.decodeUrl
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.utils.MediaUtils
import com.xyoye.common_component.utils.StatusBarStyle
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.player_component.BR
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ActivityPlayerIntentBinding

class PlayerIntentActivity : BaseActivity<PlayerIntentViewModel, ActivityPlayerIntentBinding>() {
    private lateinit var videoUri: Uri
    private lateinit var videoUrl: String

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            PlayerIntentViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_player_intent

    override fun initStatusBar() {
        StatusBarStyle.applyFullscreenHideStatusBar(this)
    }

    override fun initView() {
        observerPlay()

        dataBinding.exitTv.setOnClickListener {
            finish()
        }

        val intentData = intent.data
        if (intentData == null) {
            viewModel.isParseError.set(true)
            return
        }
        videoUri = intentData
        videoUrl = MediaUtils.getPathFromURI(videoUri)
        if (videoUrl.isEmpty()) {
            videoUrl = intentData.toString()
        } else if (videoUrl.startsWith("file://")) {
            videoUrl = videoUrl.decodeUrl()
            viewModel.addUnrecognizedFile(videoUrl)
        }

        viewModel.openIntentUrl(videoUrl)
    }

    private fun observerPlay() {
        viewModel.playEvents.collectAtStarted(this) { result ->
            when (result) {
                is PlaybackSubmissionResult.Accepted -> {
                    ARouter
                        .getInstance()
                        .build(RouteTable.Player.Player)
                        .withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                        .navigation(this@PlayerIntentActivity)
                    finish()
                }
                is PlaybackSubmissionResult.Rejected -> {
                    ToastCenter.showError(result.failure.userMessage())
                    viewModel.isParseError.set(true)
                }
            }
        }
    }
}
