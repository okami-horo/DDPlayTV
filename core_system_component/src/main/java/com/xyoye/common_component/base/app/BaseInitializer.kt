package com.xyoye.common_component.base.app

import android.content.Context
import androidx.startup.Initializer
import com.tencent.mmkv.MMKV
import java.io.File

class BaseInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val root = MMKV.initialize(context.applicationContext)
        // Only retired integration stores are removed; downloaded media/danmaku remain user files.
        File(root).listFiles()?.filter { it.isFile && it.name.startsWith("bilibili_") }?.forEach { it.delete() }
        MMKV.defaultMMKV().removeValuesForKeys(
            arrayOf(
                "key_bilibili_tv_app_key",
                "key_bilibili_tv_app_secret",
                "key_bilibili_tv_app_key_encrypted",
                "key_bilibili_tv_app_secret_encrypted",
                "key_auto_enable_bilibili_live_danmaku"
            )
        )
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf()
}
