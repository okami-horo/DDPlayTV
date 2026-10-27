package com.xyoye.user_component.ui.activities.bilibili_risk_verify

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.bilibili.net.BilibiliHeaders
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.toResColor
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.PlaybackVerificationChallenge
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityBilibiliRiskVerifyBinding
import com.xyoye.user_component.ui.weight.WebViewProgress
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Route(path = RouteTable.User.BilibiliRiskVerify)
class BilibiliRiskVerifyActivity : BaseActivity<BilibiliRiskVerifyViewModel, ActivityBilibiliRiskVerifyBinding>() {
    private val verificationId get() = intent.getStringExtra(PlaybackNavigation.VERIFICATION_ID).orEmpty()

    private var pageLoaded: Boolean = false
    private var pendingConfig: PlaybackVerificationChallenge? = null
    private lateinit var progressView: WebViewProgress

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            BilibiliRiskVerifyViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_bilibili_risk_verify

    override fun initView() {
        if (verificationId.isBlank()) {
            finish()
            return
        }

        title = getString(R.string.bilibili_risk_verify_title)
        setResult(RESULT_CANCELED, Intent().putExtra(PlaybackNavigation.VERIFICATION_ID, verificationId))

        initObserver()
        initWebView()
        loadVerifyPage()

        viewModel.start(verificationId)
    }

    private fun initObserver() {
        viewModel.geetestConfigLiveData.observe(this) { config ->
            pendingConfig = config
            applyGeetestConfigIfReady()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.outcomes.collect { result ->
                    when (result) {
                        is PlaybackSubmissionResult.Accepted -> {
                            ToastCenter.showOriginalToast(getString(R.string.bilibili_risk_verify_success))
                            setResult(
                                RESULT_OK,
                                Intent()
                                    .putExtra(PlaybackNavigation.REQUEST_ID, result.requestId)
                                    .putExtra(PlaybackNavigation.VERIFICATION_ID, verificationId)
                            )
                        }
                        is PlaybackSubmissionResult.Rejected -> {
                            ToastCenter.showError(result.failure.userMessage())
                            setResult(RESULT_CANCELED, Intent().putExtra(PlaybackNavigation.VERIFICATION_ID, verificationId))
                        }
                    }
                    finish()
                }
            }
        }
    }

    override fun finish() {
        viewModel.cancel()
        super.finish()
    }

    private fun initWebView() {
        progressView =
            WebViewProgress(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(4))
                setColor(R.color.text_blue.toResColor())
                setProgress(10)
            }

        dataBinding.webView.apply {
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            addView(progressView)
            addJavascriptInterface(GeetestBridge(), "Android")

            settings.apply {
                allowFileAccess = true
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                javaScriptCanOpenWindowsAutomatically = true
                allowUniversalAccessFromFileURLs = true
                userAgentString = BilibiliHeaders.USER_AGENT
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.webkit.CookieManager
                    .getInstance()
                    .setAcceptThirdPartyCookies(this, true)
            }

            webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String
                    ) {
                        pageLoaded = true
                        applyGeetestConfigIfReady()
                        super.onPageFinished(view, url)
                    }
                }

            webChromeClient =
                object : WebChromeClient() {
                    override fun onProgressChanged(
                        view: WebView,
                        newProgress: Int
                    ) {
                        if (newProgress == 100) {
                            progressView.isGone = true
                        } else {
                            progressView.setProgress(newProgress)
                        }
                        super.onProgressChanged(view, newProgress)
                    }
                }
        }
    }

    private fun loadVerifyPage() {
        val html =
            runCatching {
                assets.open(ASSET_GEETEST_PAGE).bufferedReader().use { it.readText() }
            }.getOrNull()

        if (html.isNullOrBlank()) {
            ToastCenter.showError(getString(R.string.bilibili_risk_verify_page_failed))
            finish()
            return
        }

        dataBinding.webView.loadDataWithBaseURL(
            BilibiliHeaders.REFERER,
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun applyGeetestConfigIfReady() {
        val config = pendingConfig ?: return
        if (!pageLoaded) return

        val js =
            "window.setupGeetest(${jsString(config.gt)}, ${jsString(config.challenge)});"
        dataBinding.webView.evaluateJavascript(js, null)
    }

    private fun jsString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private inner class GeetestBridge {
        @JavascriptInterface
        fun onSuccess(
            challenge: String?,
            validate: String?,
            seccode: String?
        ) {
            val realValidate = validate?.takeIf { it.isNotBlank() }
            val realSeccode = seccode?.takeIf { it.isNotBlank() }
            if (realValidate == null || realSeccode == null) {
                ToastCenter.showError(getString(R.string.bilibili_risk_verify_result_failed))
                return
            }
            viewModel.submitGeetestResult(
                challengeFromJs = challenge,
                validate = realValidate,
                seccode = realSeccode
            )
        }

        @JavascriptInterface
        fun onError(message: String?) {
            ToastCenter.showError(getString(R.string.bilibili_risk_verify_result_failed))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataBinding.webView.apply {
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            clearHistory()
            (parent as ViewGroup).removeView(this)
            destroy()
        }
    }

    private companion object {
        private const val ASSET_GEETEST_PAGE = "bilibili/geetest_voucher.html"
    }
}
