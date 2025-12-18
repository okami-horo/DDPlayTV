package com.xyoye.common_component.config

import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

/**
 * Created by xyoye on 2022/9/15
 */

@MMKVKotlinClass(className = "ScreencastConfig")
object ScreencastConfigTable {
    // 投屏接收端是否使用密码
    @MMKVFiled
    var useReceiverPassword: Boolean = false

    // 投屏接收端密码
    @MMKVFiled
    var receiverPassword: String? = null

    // 投屏接收端端口
    @MMKVFiled
    var receiverPort: Int = 0

    // 接收到投屏时需手动确认
    @MMKVFiled
    var receiveNeedConfirm: Boolean = true

    // 应用启动时，自动启动投屏接收服务
    @MMKVFiled
    var startReceiveWhenLaunch: Boolean = false
}
