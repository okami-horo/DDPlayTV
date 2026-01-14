package com.okamihoro.ddplaytv.ui.splash

import android.content.Intent
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity

class TvSplashActivity : BaseSplashActivity() {
    override fun createLaunchIntent(): Intent = Intent(this, TvMainActivity::class.java)
}
