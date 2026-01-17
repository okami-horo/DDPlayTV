package com.okamihoro.ddplaytv.ui.splash

import android.content.Intent
import com.okamihoro.ddplaytv.ui.main.MainActivity

/**
 * Created by xyoye on 2020/7/27.
 */

class SplashActivity : BaseSplashActivity() {
    override fun createLaunchIntent(): Intent = Intent(this, MainActivity::class.java)
}
