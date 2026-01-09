package com.xyoye.user_component.services

import android.content.Context
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.services.DeveloperMenuService
import com.xyoye.user_component.ui.weight.DeveloperMenus

@Route(path = RouteTable.App.DeveloperMenuService, name = "开发者菜单 Service")
class DeveloperMenuServiceImpl : DeveloperMenuService {
    override fun init(context: Context?) {
    }

    override fun create(
        activity: AppCompatActivity,
        menu: Menu
    ): DeveloperMenuService.Delegate =
        DeveloperMenus.inflater(activity, menu)
}

