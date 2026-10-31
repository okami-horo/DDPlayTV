package com.xyoye.user_component.ui.activities.login

import androidx.core.widget.addTextChangedListener
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.applyDpadEditTextPolicy
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityLoginBinding
import com.xyoye.user_component.ui.dialog.DeveloperAuthenticateDialog

@Route(path = RouteTable.User.UserLogin)
class LoginActivity : BaseActivity<LoginViewModel, ActivityLoginBinding>() {
    @Autowired
    @JvmField
    var userAccount: String? = null

    private var developerAuthenticateDialog: DeveloperAuthenticateDialog? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            LoginViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_login

    override fun initView() {
        ARouter.getInstance().inject(this)

        title = ""

        dataBinding.userAccountEt.applyDpadEditTextPolicy()
        dataBinding.userPasswordEt.applyDpadEditTextPolicy()
        if (!userAccount.isNullOrEmpty()) {
            viewModel.accountField.set(userAccount)
            dataBinding.userPasswordEt.requestFocus()
        } else {
            dataBinding.userAccountEt.requestFocus()
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
        viewModel.openDeveloperAuthDialogLiveData.observe(this) {
            showDeveloperAuthenticateDialog()
        }
    }

    override fun onDestroy() {
        developerAuthenticateDialog?.dismiss()
        developerAuthenticateDialog = null
        super.onDestroy()
    }

    private fun showDeveloperAuthenticateDialog() {
        developerAuthenticateDialog?.dismiss()
        developerAuthenticateDialog =
            DeveloperAuthenticateDialog(this) {
                // 登录页仅负责引导认证，认证成功后用户可手动再次点击登录。
            }
        developerAuthenticateDialog?.show()
    }
}
