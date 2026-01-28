package com.okamihoro.ddplaytv.app.service

import android.content.Context
import android.content.Intent
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.service.Media3SessionServiceProvider

@Route(
    path = RouteTable.Player.Media3SessionServiceProvider,
    name = "Media3SessionService Intent Provider",
)
class Media3SessionServiceProviderImpl : Media3SessionServiceProvider {
    override fun init(context: Context?) {
    }

    override fun createBindIntent(context: Context): Intent = Intent(context, Media3SessionService::class.java)
}

