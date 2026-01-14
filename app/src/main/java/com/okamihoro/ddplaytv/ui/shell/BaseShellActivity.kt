package com.okamihoro.ddplaytv.ui.shell

import androidx.databinding.ViewDataBinding
import androidx.lifecycle.MutableLiveData
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.bridge.LoginObserver
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.config.UserConfig
import com.xyoye.common_component.services.ScreencastReceiveService
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.okamihoro.ddplaytv.BR
import com.xyoye.data_component.data.LoginData
import kotlin.random.Random
import kotlin.system.exitProcess

abstract class BaseShellActivity<V : ViewDataBinding> :
    BaseActivity<ShellViewModel, V>(),
    LoginObserver {
    @Autowired
    lateinit var receiveService: ScreencastReceiveService

    private var touchTime = 0L

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            ShellViewModel::class.java,
        )

    override fun getLoginLiveData(): MutableLiveData<LoginData> = viewModel.reLoginLiveData

    protected fun initShell() {
        ARouter.getInstance().inject(this)

        viewModel.initCloudBlockData()
        initScreencastReceive()

        if (UserConfig.isUserLoggedIn()) {
            viewModel.reLogin()
        }
    }

    protected fun handleBackExit(): Boolean {
        if (checkServiceExit()) {
            return true
        }

        if (System.currentTimeMillis() - touchTime > 1500) {
            ToastCenter.showToast("再按一次退出应用")
            touchTime = System.currentTimeMillis()
            return true
        }
        return false
    }

    private fun initScreencastReceive() {
        if (ScreencastConfig.isStartReceiveWhenLaunch().not()) {
            return
        }
        if (receiveService.isRunning(this)) {
            return
        }

        var httpPort = ScreencastConfig.getReceiverPort()
        if (httpPort == 0) {
            httpPort = Random.nextInt(20000, 30000)
            ScreencastConfig.putReceiverPort(httpPort)
        }
        val receiverPwd = ScreencastConfig.getReceiverPassword()
        receiveService.startService(this, httpPort, receiverPwd)
    }

    private fun checkServiceExit(): Boolean {
        val isReceiveServiceRunning = receiveService.isRunning(this)
        if (isReceiveServiceRunning.not()) {
            return false
        }
        CommonDialog
            .Builder(this)
            .run {
                tips = "确认退出？"
                content = "投屏接收服务正在运行中，退出将中断投屏"
                addNegative()
                addPositive("退出") {
                    it.dismiss()
                    receiveService.stopService(this@BaseShellActivity)
                    finish()
                    exitProcess(0)
                }
                build()
            }.show()
        return true
    }
}
