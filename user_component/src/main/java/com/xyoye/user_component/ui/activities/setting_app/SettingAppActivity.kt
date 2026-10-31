package com.xyoye.user_component.ui.activities.setting_app

import android.view.KeyEvent
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivitySettingAppBinding
import com.xyoye.user_component.ui.fragment.AppSettingFragment
import com.xyoye.user_component.ui.fragment.DanmuSettingFragment
import com.xyoye.user_component.ui.fragment.PlayerSettingFragment
import com.xyoye.user_component.ui.fragment.SubtitleSettingFragment

@Route(path = RouteTable.User.SettingApp)
class SettingAppActivity : BaseActivity<SettingAppViewModel, ActivitySettingAppBinding>() {
    private var pages: TvCategoryPages? = null
    private var initialFocusPending = true

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SettingAppViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_setting_app

    override fun initView() {
        title = "设置"
        pages =
            TvCategoryPages(
                this,
                supportFragmentManager,
                dataBinding.categories,
                dataBinding.fragmentContainer,
                verticalNavigation = true
            ).apply {
                submit(
                    listOf(
                        TvCategory("general", "通用") { AppSettingFragment.newInstance() },
                        TvCategory("playback", "播放") { PlayerSettingFragment.newInstance() },
                        TvCategory("subtitles", "字幕") { SubtitleSettingFragment.newInstance() },
                        TvCategory("danmaku", "弹幕") { DanmuSettingFragment.newInstance() }
                    )
                )
            }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && initialFocusPending) {
            initialFocusPending = false
            pages?.requestNavigationFocus()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && dataBinding.fragmentContainer.hasFocus()) {
            return pages?.requestNavigationFocus() == true
        }
        return super.onKeyDown(keyCode, event)
    }
}
