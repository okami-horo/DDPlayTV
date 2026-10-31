package com.xyoye.user_component.ui.activities.setting_player

import android.view.KeyEvent
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivitySettingPlayerBinding
import com.xyoye.user_component.ui.fragment.DanmuSettingFragment
import com.xyoye.user_component.ui.fragment.PlayerSettingFragment
import com.xyoye.user_component.ui.fragment.SubtitleSettingFragment

@Route(path = RouteTable.User.SettingPlayer)
class SettingPlayerActivity : BaseActivity<SettingPlayerViewModel, ActivitySettingPlayerBinding>() {
    private var tvPages: TvCategoryPages? = null
    private var initialFocusPending = true

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SettingPlayerViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_setting_player

    override fun initView() {
        title = "播放器设置"

        tvPages =
            TvCategoryPages(this, supportFragmentManager, dataBinding.tabLayout, dataBinding.viewpager, verticalNavigation = true).apply {
                submit(
                    listOf(
                        TvCategory("video", "视频") { PlayerSettingFragment.newInstance() },
                        TvCategory("danmu", "弹幕") { DanmuSettingFragment.newInstance() },
                        TvCategory("subtitle", "字幕") { SubtitleSettingFragment.newInstance() }
                    ),
                    initialIndex = intent.getIntExtra("initialCategory", 0)
                )
            }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !initialFocusPending) return
        initialFocusPending = false
        val pages = tvPages ?: return
        if (!dataBinding.root.isInTouchMode && !pages.content.hasFocus()) pages.requestNavigationFocus()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && dataBinding.viewpager.hasFocus()) {
            return tvPages?.requestNavigationFocus() == true
        }
        return super.onKeyDown(keyCode, event)
    }
}
