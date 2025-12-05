package com.xyoye.dandanplay.ui.debug

import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.dandanplay.BR
import com.xyoye.dandanplay.R
import com.xyoye.dandanplay.databinding.ActivityLoggingConfigBinding

/**
 * 日志配置页骨架，后续补充具体交互与展示。
 */
@Route(path = RouteTable.User.LoggingConfig)
class LoggingConfigActivity :
    BaseActivity<LoggingConfigViewModel, ActivityLoggingConfigBinding>() {

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            LoggingConfigViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_logging_config

    override fun initView() {
        title = "日志配置"
    }
}
