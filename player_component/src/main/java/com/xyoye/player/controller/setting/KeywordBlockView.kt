package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutKeywordBlockBinding

/**
 * Created by xyoye on 2021/2/18.
 */

class KeywordBlockView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutKeywordBlockBinding>(context, attrs, defStyleAttr) {
    private var addKeyword: ((keyword: String, isRegex: Boolean) -> Unit)? = null
    private var removeKeyword: ((id: Int) -> Unit)? = null
    private val keywordList = mutableListOf<DanmuBlockEntity>()

    init {
        initSettingListener()
    }

    override fun getLayoutId() = R.layout.layout_keyword_block

    override fun getSettingViewType() = SettingViewType.KEYWORD_BLOCK

    override fun getGravity() = Gravity.CENTER

    override fun onViewShowed() {
        viewBinding.ivClose.requestFocus()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (isSettingShowing().not()) {
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            closeSettingView()
            return true
        }

        val handled = handleKeyCode(keyCode)
        if (handled) {
            return true
        }

        // 拦截所有按键，防止误触底层播放器
        // 如果焦点丢失，重置焦点到输入框
        if (viewBinding.root.findFocus() == null) {
            viewBinding.keywordBlockAddEt.requestFocus()
        }
        return true
    }

    private fun initSettingListener() {
        viewBinding.ivClose.setOnClickListener {
            closeSettingView()
        }

        viewBinding.keywordBlockLl.setOnClickListener {
            closeSettingView()
        }

        viewBinding.keywordLabelsView.setOnLabelClickListener { _, data, _ ->
            if (data is DanmuBlockEntity) {
                mControlWrapper.removeBlackList(data.isRegex, data.keyword)
                removeKeyword?.invoke(data.id)
            }
        }

        viewBinding.keywordBlockAddTv.setOnClickListener {
            viewBinding.keywordBlockLl.requestFocus()

            var isRegex = false
            var newKeyword = viewBinding.keywordBlockAddEt.text.toString()

            if (newKeyword.isEmpty()) {
                ToastCenter.showOriginalToast("关键字不能为空")
                return@setOnClickListener
            }
            if ("regex=" == newKeyword) {
                ToastCenter.showOriginalToast("正则表达式内容不能为空")
                return@setOnClickListener
            }

            // 是否为正则表达式
            if (newKeyword.startsWith("regex=")) {
                newKeyword = newKeyword.substring(6)
                isRegex = true
            }

            viewBinding.keywordBlockAddEt.setText("")
            mControlWrapper.addBlackList(isRegex, newKeyword)
            addKeyword?.invoke(newKeyword, isRegex)
        }

        viewBinding.keywordBlockAddEt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(viewBinding.keywordBlockAddEt)
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    fun setDatabaseBlock(
        add: ((keyword: String, isRegex: Boolean) -> Unit),
        remove: ((id: Int) -> Unit),
        queryAll: () -> LiveData<MutableList<DanmuBlockEntity>>
    ) {
        addKeyword = add
        removeKeyword = remove
        queryAll.invoke().observe(context as LifecycleOwner) {
            keywordList.clear()
            keywordList.addAll(it)
            viewBinding.keywordLabelsView.setLabels(keywordList) { _, _, data ->
                data?.keyword ?: ""
            }

            // 设置正则类型的标签为选中状态
            val regexPositionList = mutableListOf<Int>()
            for ((index, entity) in it.withIndex()) {
                if (entity.isRegex) {
                    regexPositionList.add(index)
                }
            }
            viewBinding.keywordLabelsView.setSelects(regexPositionList)

            // 按正则和关键字屏蔽
            val regexList = mutableListOf<String>()
            val keywordList = mutableListOf<String>()
            it.forEach { entity ->
                if (entity.isRegex) {
                    regexList.add(entity.keyword)
                } else {
                    keywordList.add(entity.keyword)
                }
            }
            mControlWrapper.addBlackList(false, *keywordList.toTypedArray())
            mControlWrapper.addBlackList(true, *regexList.toTypedArray())
        }
    }

    private fun handleKeyCode(keyCode: Int): Boolean {
        if (viewBinding.keywordLabelsView.focusedChild != null) {
            val handled = handleKeyLabelsView(keyCode)
            if (handled) {
                return true
            }
        }

        if (viewBinding.ivClose.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                viewBinding.keywordBlockAddEt.requestFocus()
                return true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return true
            }
        }
        
        if (viewBinding.keywordBlockAddEt.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                viewBinding.keywordLabelsView.getChildAt(0)?.requestFocus()
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                viewBinding.ivClose.requestFocus()
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                viewBinding.keywordBlockAddTv.requestFocus()
            }
            return true
        }

        if (viewBinding.keywordBlockAddTv.hasFocus()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> viewBinding.keywordBlockAddEt.requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    viewBinding.keywordLabelsView
                        .getChildAt(0)
                        ?.requestFocus()
                 KeyEvent.KEYCODE_DPAD_UP -> viewBinding.ivClose.requestFocus()
            }
            return true
        }

        return handleFocusSearch(keyCode)
    }

    private fun handleKeyLabelsView(keyCode: Int): Boolean {
        val focusedChild =
            viewBinding.keywordLabelsView.focusedChild
                ?: return false
        val direction = keyCodeToDirection(keyCode) ?: return false
        val next =
            FocusFinder.getInstance()
                .findNextFocus(viewBinding.keywordLabelsView, focusedChild, direction)

        if (next != null) {
            next.requestFocus()
            return true
        }

        if (direction == View.FOCUS_UP) {
            viewBinding.keywordBlockAddEt.requestFocus()
        }
        return true
    }

    private fun handleFocusSearch(keyCode: Int): Boolean {
        val direction = keyCodeToDirection(keyCode) ?: return false
        val root = viewBinding.root as? ViewGroup ?: return false
        val focused = root.findFocus() ?: return false
        val next = FocusFinder.getInstance().findNextFocus(root, focused, direction)
        if (next == null) {
            return false
        }
        if (next === viewBinding.keywordBlockLl) {
            return false
        }
        next.requestFocus()
        return true
    }

    private fun keyCodeToDirection(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> null
        }
    }

    private fun closeSettingView() {
        mControlWrapper.hideSettingView()
    }
}
