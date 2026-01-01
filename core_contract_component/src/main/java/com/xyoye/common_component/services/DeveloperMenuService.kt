package com.xyoye.common_component.services

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.android.arouter.facade.template.IProvider

/**
 * 开发者菜单（MainActivity 标题栏菜单）能力
 *
 * 壳层通过该 Service 解耦对 feature 内部实现的直接依赖。
 */
interface DeveloperMenuService : IProvider {
    interface Delegate {
        fun onOptionsItemSelected(item: MenuItem): Boolean
    }

    fun create(
        activity: AppCompatActivity,
        menu: Menu
    ): Delegate
}

