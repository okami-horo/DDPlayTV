package com.xyoye.common_component.service

import android.content.Context
import android.content.Intent
import com.alibaba.android.arouter.facade.template.IProvider

/**
 * 用于获取 Media3SessionService 的显式 Intent，避免 feature 模块硬编码 :app Service 类名。
 */
interface Media3SessionServiceProvider : IProvider {
    fun createBindIntent(context: Context): Intent
}

