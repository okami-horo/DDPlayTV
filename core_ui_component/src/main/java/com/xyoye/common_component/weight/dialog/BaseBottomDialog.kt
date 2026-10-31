package com.xyoye.common_component.weight.dialog

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.xyoye.common_component.utils.dp2px
import com.xyoye.core_ui_component.R
import com.xyoye.core_ui_component.databinding.DialogBaseBottomDialogBinding

/**
 * Created by xyoye on 2020/12/22.
 */

abstract class BaseBottomDialog<T : ViewDataBinding>(
    private val activity: Activity
) : AppCompatDialog(activity, R.style.CinemaDialog) {
    protected lateinit var rootViewBinding: DialogBaseBottomDialogBinding
    private var initialFocusPending = false
    protected open val contentWidthDp = 440

    override fun show() {
        initialFocusPending = true
        super.show()
    }

    override fun onStart() {
        super.onStart()
        window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && initialFocusPending && !rootViewBinding.root.isInTouchMode) {
            initialFocusPending = false
            if (!rootViewBinding.containerFl.requestFocus()) {
                if (!rootViewBinding.negativeBt.requestFocus()) rootViewBinding.positiveBt.requestFocus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layoutInflater = LayoutInflater.from(context)
        rootViewBinding =
            DataBindingUtil.inflate(
                layoutInflater,
                R.layout.dialog_base_bottom_dialog,
                null,
                false,
            )
        rootViewBinding.root.isFocusable = false

        val childViewBinding =
            DataBindingUtil.inflate<T>(
                layoutInflater,
                getChildLayoutId(),
                rootViewBinding.containerFl,
                true,
            )

        window?.apply {
            setSoftInputMode(
                (attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            decorView.setPadding(0, 0, 0, 0)

            val layoutParams = attributes
            layoutParams.width = minOf(dp2px(contentWidthDp), context.resources.displayMetrics.widthPixels - dp2px(48))
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            attributes = layoutParams

            setGravity(Gravity.CENTER)
        }

        setContentView(rootViewBinding.root)

        initView(childViewBinding)
        listOf(rootViewBinding.actionLeftContainer, rootViewBinding.actionRightContainer).forEach {
            if (it.childCount == 0) it.layoutParams = it.layoutParams.apply { width = dp2px(10) }
        }
    }

    protected fun setTitle(text: String) {
        rootViewBinding.titleTv.text = text
    }

    protected fun setPositiveText(text: String) {
        rootViewBinding.positiveBt.text = text
    }

    protected fun setNegativeText(text: String) {
        rootViewBinding.negativeBt.text = text
    }

    protected fun setPositiveListener(block: () -> Unit) {
        rootViewBinding.positiveBt.setOnClickListener { block.invoke() }
    }

    protected fun setNegativeListener(block: () -> Unit) {
        rootViewBinding.negativeBt.setOnClickListener { block.invoke() }
    }

    protected fun setPositiveVisible(visible: Boolean) {
        rootViewBinding.positiveBt.isVisible = visible
    }

    protected fun setNegativeVisible(visible: Boolean) {
        rootViewBinding.negativeBt.isVisible = visible
    }

    protected fun setBottomActionVisible(visible: Boolean) {
        rootViewBinding.bottomActionLl.isVisible = visible
    }

    protected fun removeParentPadding() {
        rootViewBinding.containerFl.setPadding(0)
    }

    protected fun addNeutralButton(
        text: String,
        block: () -> Unit
    ) {
        rootViewBinding.neutralBt.apply {
            isVisible = true
            setText(text)
            setOnClickListener { block.invoke() }
        }
    }

    protected fun addLeftAction(
        drawable: Drawable?,
        paddingDp: Int = 6,
        description: String = ""
    ): View {
        val actionView = createActionView(drawable, description, paddingDp)
        rootViewBinding.actionLeftContainer.apply {
            when (childCount) {
                0 -> {
                    val layoutParams = actionLayoutParams(Gravity.START)
                    addView(actionView, layoutParams)
                }

                1 -> {
                    val layoutParams = actionLayoutParams(Gravity.END)
                    addView(actionView, layoutParams)
                }
            }
        }
        return actionView
    }

    protected fun addRightAction(
        drawable: Drawable?,
        paddingDp: Int = 6,
        description: String = ""
    ): View {
        val actionView = createActionView(drawable, description, paddingDp)
        rootViewBinding.actionRightContainer.apply {
            when (childCount) {
                0 -> {
                    val layoutParams = actionLayoutParams(Gravity.END)
                    addView(actionView, layoutParams)
                }

                1 -> {
                    val layoutParams = actionLayoutParams(Gravity.START)
                    addView(actionView, layoutParams)
                }
            }
        }
        return actionView
    }

    private fun createActionView(
        drawable: Drawable?,
        description: String,
        paddingDp: Int
    ): View {
        val padding = dp2px(paddingDp)
        val actionView = ImageView(context)
        actionView.setImageDrawable(drawable)
        actionView.setPadding(padding, padding, padding, padding)
        actionView.setBackgroundResource(R.drawable.cinema_icon_circle)
        actionView.isFocusable = true
        actionView.contentDescription = description
        return actionView
    }

    private fun actionLayoutParams(gravity: Int): FrameLayout.LayoutParams {
        val size = dp2px(36)
        val layoutParams = FrameLayout.LayoutParams(size, size)
        layoutParams.gravity = gravity
        return layoutParams
    }

    protected fun setDialogCancelable(
        touchCancel: Boolean,
        backPressedCancel: Boolean
    ) {
        setCancelable(backPressedCancel)
        setCanceledOnTouchOutside(touchCancel)
    }

    abstract fun getChildLayoutId(): Int

    abstract fun initView(binding: T)
}
