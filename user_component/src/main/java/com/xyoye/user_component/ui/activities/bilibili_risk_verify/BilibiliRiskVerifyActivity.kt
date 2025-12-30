package com.xyoye.user_component.ui.activities.bilibili_risk_verify

import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isGone
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.bilibili.net.BilibiliHeaders
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.toResColor
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityBilibiliRiskVerifyBinding
import com.xyoye.user_component.ui.weight.WebViewProgress

@SuppressLint("SetJavaScriptEnabled")
@Route(path = RouteTable.User.BilibiliRiskVerify)
class BilibiliRiskVerifyActivity : BaseActivity<BilibiliRiskVerifyViewModel, ActivityBilibiliRiskVerifyBinding>() {
    @JvmField
    @Autowired
    var storageKey: String = ""

    @JvmField
    @Autowired
    var vVoucher: String = ""

    private var pageLoaded: Boolean = false
    private var pendingConfig: BilibiliRiskVerifyViewModel.GeetestConfig? = null
    private lateinit var progressView: WebViewProgress

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            BilibiliRiskVerifyViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_bilibili_risk_verify

    override fun initView() {
        ARouter.getInstance().inject(this)

        if (storageKey.isBlank() || vVoucher.isBlank()) {
            finish()
            return
        }

        title = getString(R.string.bilibili_risk_verify_title)

        initObserver()
        initWebView()
        loadVerifyPage()

        viewModel.start(storageKey, vVoucher)
    }

    private fun initObserver() {
        viewModel.geetestConfigLiveData.observe(this) { config ->
            pendingConfig = config
            applyGeetestConfigIfReady()
        }
        viewModel.verifySuccessLiveData.observe(this) {
            ToastCenter.showOriginalToast(getString(R.string.bilibili_risk_verify_success))
            setResult(RESULT_OK)
            finish()
        }
        viewModel.errorLiveData.observe(this) { message ->
            ToastCenter.showError(message)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun initWebView() {
        progressView =
            WebViewProgress(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(4))
                setColor(R.color.text_blue.toResColor())
                setProgress(10)
            }

        dataBinding.webView.apply {
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
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
            null,
        )
    }

    private fun applyGeetestConfigIfReady() {
        val config = pendingConfig ?: return
        if (!pageLoaded) return

        val js =
            "window.setupGeetest(${jsString(config.gt)}, ${jsString(config.challenge)});"
        dataBinding.webView.evaluateJavascript(js, null)
    }

    private fun jsString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private inner class GeetestBridge {
        @JavascriptInterface
        fun onSuccess(
            challenge: String?,
            validate: String?,
            seccode: String?,
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
                seccode = realSeccode,
            )
        }

        @JavascriptInterface
        fun onError(message: String?) {
            ToastCenter.showError(message ?: getString(R.string.bilibili_risk_verify_result_failed))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataBinding.webView.apply {
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

