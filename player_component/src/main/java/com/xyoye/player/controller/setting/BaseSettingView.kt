package com.xyoye.player.controller.setting

import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.ViewPropertyAnimatorListener
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.xyoye.common_component.utils.dp2px
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.wrapper.ControlWrapper

/**
 * Created by xyoye on 2022/1/10
 */
abstract class BaseSettingView<V : ViewDataBinding>
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr),
        InterSettingView {
        protected lateinit var mControlWrapper: ControlWrapper

        private val settingGravity by lazy { getGravity() }
        private val settingLayoutId by lazy { getLayoutId() }
        private val parentView by lazy { this }

        private var settingShowing = false

        private val defaultSettingWidth: Float by lazy {
            when (settingGravity) {
                Gravity.END -> dp2px(320).toFloat()
                Gravity.START -> -dp2px(320).toFloat()
                Gravity.CENTER -> 0f
                else -> throw IllegalArgumentException("Illegal setting view gravity: ${javaClass.simpleName}")
            }
        }

        protected val viewBinding =
            DataBindingUtil.inflate<V>(
                LayoutInflater.from(context),
                settingLayoutId,
                parentView,
                true,
            )!!

        override fun attach(controlWrapper: ControlWrapper) {
            mControlWrapper = controlWrapper
        }

        override fun getView(): View = this

        override fun onSettingVisibilityChanged(isVisible: Boolean) {
            settingShowing = isVisible
            if (settingGravity == Gravity.CENTER) {
                handleCenterVisibilityChanged(isVisible)
            } else {
                handleSideVisibilityChanged(isVisible)
            }
        }

        override fun isSettingShowing(): Boolean {
            return settingShowing
        }

        private fun handleCenterVisibilityChanged(isVisible: Boolean) {
            if (isVisible) {
                viewBinding.root.visibility = View.VISIBLE
                ViewCompat
                    .animate(viewBinding.root)
                    .alpha(1f)
                    .setDuration(500)
                    .setListener(
                        object : ViewPropertyAnimatorListener {
                            override fun onAnimationStart(view: View) {
                                onViewShow()
                            }

                            override fun onAnimationEnd(view: View) {
                                onViewShowed()
                            }

                            override fun onAnimationCancel(view: View) {
                                onViewHide()
                            }
                        },
                    ).start()
            } else {
                ViewCompat
                    .animate(viewBinding.root)
                    .alpha(0f)
                    .setDuration(500)
                    .setListener(
                        object : ViewPropertyAnimatorListener {
                            override fun onAnimationStart(view: View) {
                                onViewHide()
                            }

                            override fun onAnimationEnd(view: View) {
                                view.visibility = View.GONE
                            }

                            override fun onAnimationCancel(view: View) {
                            }
                        },
                    ).start()
            }
        }

        private fun handleSideVisibilityChanged(isVisible: Boolean) {
            if (isVisible) {
                ViewCompat
                    .animate(viewBinding.root)
                    .translationX(0f)
                    .setDuration(500)
                    .setListener(
                        object : ViewPropertyAnimatorListener {
                            override fun onAnimationStart(view: View) {
                                onViewShow()
                            }

                            override fun onAnimationEnd(view: View) {
                                onViewShowed()
                            }

                            override fun onAnimationCancel(view: View) {
                                onViewHide()
                            }
                        },
                    ).start()
            } else {
                ViewCompat
                    .animate(viewBinding.root)
                    .translationX(defaultSettingWidth)
                    .setDuration(500)
                    .setListener(
                        object : ViewPropertyAnimatorListener {
                            override fun onAnimationStart(view: View) {
                                onViewHide()
                            }

                            override fun onAnimationEnd(view: View) {
                            }

                            override fun onAnimationCancel(view: View) {
                            }
                        },
                    ).start()
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
        }

        override fun onPlayStateChanged(playState: PlayState) {
        }

        override fun onProgressChanged(
            duration: Long,
            position: Long
        ) {
        }

        override fun onLockStateChanged(isLocked: Boolean) {
        }

        override fun onPopupModeChanged(isPopup: Boolean) {
        }

        override fun onVideoSizeChanged(videoSize: Point) {
        }

        override fun onTrackChanged(type: TrackType) {
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent?
        ): Boolean = false

        open fun getGravity() = Gravity.END

        open fun onViewShow() {
        }

        open fun onViewShowed() {
        }

        open fun onViewHide() {
        }

        abstract fun getLayoutId(): Int
    }
