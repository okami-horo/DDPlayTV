package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemSettingTracksBinding
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

    init {
        initSettingListener()
    }

    override fun getLayoutId() = R.layout.layout_keyword_block

    override fun getSettingViewType() = SettingViewType.KEYWORD_BLOCK

    override fun getGravity() = Gravity.CENTER

    override fun onViewShowed() {
        viewBinding.ivClose.requestFocus()
    }

    private fun initSettingListener() {
        viewBinding.keywordRv.apply {
            layoutManager = vertical()
            adapter =
                buildAdapter {
                    setupDiffUtil { areItemsTheSame { old, new -> (old as DanmuBlockEntity).id == (new as DanmuBlockEntity).id } }
                    addItem<DanmuBlockEntity, ItemSettingTracksBinding>(R.layout.item_setting_tracks) {
                        initView { data, _, _ ->
                            itemBinding.tvName.text = data.keyword
                            itemBinding.tvName.isSelected = data.isRegex
                            itemBinding.tvName.setOnClickListener {
                                mControlWrapper.removeBlackList(data.isRegex, data.keyword)
                                removeKeyword?.invoke(data.id)
                            }
                        }
                    }
                }
        }
        viewBinding.ivClose.setOnClickListener {
            closeSettingView()
        }

        viewBinding.keywordBlockLl.setOnClickListener {
            closeSettingView()
        }

        viewBinding.keywordBlockAddTv.setOnClickListener {
            var isRegex = false
            var newKeyword = viewBinding.keywordBlockAddEt.text.toString()

            if (newKeyword.isEmpty()) {
                ToastCenter.showOriginalToast("关键字不能为空")
                viewBinding.keywordBlockAddEt.requestFocus()
                return@setOnClickListener
            }
            if ("regex=" == newKeyword) {
                ToastCenter.showOriginalToast("正则表达式内容不能为空")
                viewBinding.keywordBlockAddEt.requestFocus()
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
            viewBinding.keywordBlockAddEt.requestFocus()
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
            viewBinding.keywordRv.setData(it)

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

    private fun closeSettingView() {
        mControlWrapper.hideSettingView()
    }
}
