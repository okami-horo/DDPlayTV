package com.xyoye.user_component.ui.fragment

import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.FragmentCinemaSettingsBinding
import com.xyoye.user_component.ui.activities.setting_app.SettingAppViewModel

@Route(path = RouteTable.User.SettingsFragment)
class CinemaSettingsFragment :
    BaseFragment<SettingAppViewModel, FragmentCinemaSettingsBinding>(),
    DpadFocusOwner {
    private var pages: TvCategoryPages? = null

    override fun initViewModel() = ViewModelInit(BR.viewModel, SettingAppViewModel::class.java)

    override fun getLayoutId() = R.layout.fragment_cinema_settings

    override fun initView() {
        val categories =
            listOf(
                TvCategory("playback", "播放") { PlayerSettingFragment.newInstance(compact = true) },
                TvCategory("captions", "字幕与弹幕") { CinemaCaptionPreferences() },
                TvCategory("media", "媒体与存储") { AppSettingFragment.newInstance("media") },
                TvCategory("about", "关于") { AppSettingFragment.newInstance("about") }
            )
        pages =
            TvCategoryPages(
                this,
                childFragmentManager,
                dataBinding.categories,
                dataBinding.settingsContent,
                verticalNavigation = true,
                onSelected = { index -> dataBinding.sectionTitle.text = categories[index].title }
            ).apply { submit(categories) }
    }

    override fun requestDefaultDpadFocus() = pages?.requestNavigationFocus() == true

    override fun handleDpadBack() = bindingOrNull?.settingsContent?.hasFocus() == true && pages?.requestNavigationFocus() == true

    override fun onDestroyView() {
        pages = null
        super.onDestroyView()
    }
}
