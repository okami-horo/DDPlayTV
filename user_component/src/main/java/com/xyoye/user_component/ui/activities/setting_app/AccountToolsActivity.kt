package com.xyoye.user_component.ui.activities.setting_app

import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityAccountToolsBinding
import com.xyoye.user_component.ui.fragment.personal.PersonalFragment

@Route(path = RouteTable.User.AccountTools)
class AccountToolsActivity : BaseActivity<SettingAppViewModel, ActivityAccountToolsBinding>() {
    override fun initViewModel() = ViewModelInit(BR.viewModel, SettingAppViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_account_tools

    override fun initView() {
        title = "账户与工具"
        if (supportFragmentManager.findFragmentById(R.id.account_content) == null) {
            supportFragmentManager.beginTransaction().replace(R.id.account_content, PersonalFragment()).commitNow()
        }
        dataBinding.root.post {
            (
                supportFragmentManager.findFragmentById(
                    R.id.account_content
                ) as? PersonalFragment
            )?.requestDefaultDpadFocus()
        }
    }
}
