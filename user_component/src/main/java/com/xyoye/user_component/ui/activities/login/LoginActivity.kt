package com.xyoye.user_component.ui.activities.login

import android.view.View
import androidx.core.widget.addTextChangedListener
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.utils.showKeyboard
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityLoginBinding

@Route(path = RouteTable.User.UserLogin)
class LoginActivity : BaseActivity<LoginViewModel, ActivityLoginBinding>() {

    @Autowired
    @JvmField
    var userAccount: String? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            LoginViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_login

    override fun initView() {
        ARouter.getInstance().inject(this)

        title = ""

        if (!userAccount.isNullOrEmpty()) {
            viewModel.accountField.set(userAccount)
            showKeyboardWithView(dataBinding.userPasswordEt)
        } else {
            showKeyboardWithView(dataBinding.userAccountEt)
        }

        dataBinding.apply {
            userAccountEt.addTextChangedListener {
                userAccountLayout.error = ""
            }
            userPasswordEt.addTextChangedListener {
                userAccountLayout.error = ""
            }

        }

        viewModel.accountErrorLiveData.observe(this) {
            dataBinding.userAccountLayout.error = it
        }
        viewModel.passwordErrorLiveData.observe(this) {
            dataBinding.userPasswordLayout.error = it
        }
        viewModel.loginLiveData.observe(this) {
            finish()
        }
    }

    private fun showKeyboardWithView(view: View) {
        view.postDelayed({
            showKeyboard(view)
        }, 200)
    }
}
