package com.xyoye.user_component.ui.activities.setting_developer

import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivitySettingDeveloperBinding
import com.xyoye.user_component.ui.fragment.DeveloperSettingFragment

/**
 * 开发者设置 Activity，容纳偏好设置 Fragment。
 */
@Route(path = RouteTable.User.SettingDeveloper)
class SettingDeveloperActivity :
    BaseActivity<SettingDeveloperViewModel, ActivitySettingDeveloperBinding>() {

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SettingDeveloperViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_setting_developer

    override fun initView() {
        title = "开发者设置"

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                DeveloperSettingFragment.newInstance(),
                "DeveloperSettingFragment"
            )
            .commit()
    }
}
