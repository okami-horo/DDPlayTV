package com.okamihoro.ddplaytv.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import com.gyf.immersionbar.ImmersionBar
import com.xyoye.common_component.base.BaseAppCompatActivity
import com.xyoye.common_component.config.AppConfig
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.databinding.ActivitySplashBinding
import com.okamihoro.ddplaytv.utils.image_anim.path.TextPathAnimView

@SuppressLint("CustomSplashScreen")
abstract class BaseSplashActivity : BaseAppCompatActivity<ActivitySplashBinding>() {
    override fun getLayoutId() = R.layout.activity_splash

    override fun initStatusBar() {
        ImmersionBar
            .with(this)
            .transparentBar()
            .statusBarDarkFont(false)
            .init()
    }

    override fun initView() {
        window.run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            statusBarColor = Color.TRANSPARENT
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }

        if (!AppConfig.isShowSplashAnimation()) {
            launchActivity()
            return
        }

        val alphaAnimation = AlphaAnimation(0f, 1f)
        alphaAnimation.apply {
            repeatCount = Animation.ABSOLUTE
            interpolator = LinearInterpolator()
            duration = 2000
        }

        val appName = "${getString(R.string.app_name)} v${
            packageManager.getPackageInfo(packageName, 0).versionName
        }"

        dataBinding.run {
            appNameTv.text = appName

            textPathView.setAnimListener(
                object : TextPathAnimView.AnimListener {
                    override fun onStart() {
                    }

                    override fun onEnd() {
                        textPathView.postDelayed({
                            launchActivity()
                        }, 350)
                    }

                    override fun onLoop() {
                    }
                },
            )
            textPathView.startAnim()
            iconSvgView.start()
            appNameLl.startAnimation(alphaAnimation)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean =
        if (event!!.keyCode == KeyEvent.KEYCODE_BACK) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }

    override fun onDestroy() {
        dataBinding.textPathView.cancelAnim()
        super.onDestroy()
    }

    protected abstract fun createLaunchIntent(): Intent

    private fun launchActivity() {
        startActivity(createLaunchIntent())
        overridePendingTransition(R.anim.anim_activity_enter, R.anim.anim_activity_exit)
        finish()
    }
}
