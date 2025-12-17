package com.xyoye.common_component.config

import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

/**
 *    author: xyoye1997@outlook.com
 *    time  : 2025/1/22
 *    desc  : 开发者配置表
 */

@MMKVKotlinClass(className = "DevelopConfig")
object DevelopConfigTable {
    // AppId
    @MMKVFiled
    const val appId = ""

    // App Secret
    @MMKVFiled
    const val appSecret = ""

    // 是否已自动显示认证弹窗
    @MMKVFiled
    const val isAutoShowAuthDialog = false

    // 是否启用 DDLog（本地/控制台日志输出）
    @MMKVFiled
    const val ddLogEnable = false

    // 是否输出字幕遥测 Debug 日志
    @MMKVFiled
    const val subtitleTelemetryLogEnable = false
}
