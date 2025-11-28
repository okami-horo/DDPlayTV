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

    // WebDAV 日志上传开关
    @MMKVFiled
    const val logUploadEnable = false

    // WebDAV 日志上传地址
    @MMKVFiled
    const val logUploadUrl = ""

    // WebDAV 用户名
    @MMKVFiled
    const val logUploadUsername = ""

    // WebDAV 密码
    @MMKVFiled
    const val logUploadPassword = ""

    // WebDAV 日志远程目录
    @MMKVFiled
    const val logUploadRemotePath = "logs"

    // WebDAV 日志最近上传时间（毫秒）
    @MMKVFiled
    const val logUploadLastTime = 0L

    // 是否启用 DDLog（本地/控制台日志输出）
    @MMKVFiled
    const val ddLogEnable = false

    // 是否输出字幕遥测 Debug 日志
    @MMKVFiled
    const val subtitleTelemetryLogEnable = false
}
