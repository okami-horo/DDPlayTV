package com.xyoye.dandanplay.ui.splash

import android.content.Intent
import com.xyoye.dandanplay.ui.tv.TvMainActivity

class TvSplashActivity : BaseSplashActivity() {
    override fun createLaunchIntent(): Intent = Intent(this, TvMainActivity::class.java)
}
