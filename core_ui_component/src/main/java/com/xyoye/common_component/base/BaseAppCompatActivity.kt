package com.xyoye.common_component.base

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.xyoye.common_component.utils.StatusBarStyle
import com.xyoye.core_ui_component.R
import java.lang.ref.WeakReference

/**
 * Created by xyoye on 2020/7/7.
 */

abstract class BaseAppCompatActivity<V : ViewDataBinding> :
    AppCompatActivity(),
    LoadingHost {
    protected lateinit var dataBinding: V

    protected var loadingReference: WeakReference<BaseLoadingDialog>? = null

    protected var mToolbar: Toolbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initStatusBar()
        StatusBarStyle.applyFullscreenHideAllBars(this)

        initDataBinding()

        initToolbar()

        initView()
    }

    protected fun initDataBinding() {
        dataBinding = DataBindingUtil.setContentView(this, getLayoutId())
        dataBinding.lifecycleOwner = this@BaseAppCompatActivity
    }

    private fun initToolbar() {
        mToolbar = findViewById(R.id.toolbar)
        mToolbar?.apply {
            this@BaseAppCompatActivity.setSupportActionBar(this)
            touchscreenBlocksFocus = false
            generateSequence(parent as? ViewGroup) { it.parent as? ViewGroup }.forEach {
                it.touchscreenBlocksFocus = false
            }
        }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
            this@BaseAppCompatActivity.setSupportActionBar(mToolbar)
        }

        mToolbar?.setNavigationOnClickListener { finish() }
    }

    fun showLoading() {
        showLoading("")
    }

    override fun showLoading(msg: String) {
        hideLoading()
        loadingReference = WeakReference(BaseLoadingDialog(this, msg))
        if (!isFinishing) {
            loadingReference!!.get()?.show()
        }
    }

    override fun hideLoading() {
        loadingReference?.get()?.dismiss()
    }

    override fun onDestroy() {
        hideLoading()
        loadingReference = null
        super.onDestroy()
    }

    abstract fun initStatusBar()

    abstract fun getLayoutId(): Int

    abstract fun initView()
}
