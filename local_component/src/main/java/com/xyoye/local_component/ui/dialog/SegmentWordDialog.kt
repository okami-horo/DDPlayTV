package com.xyoye.local_component.ui.dialog

import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.common_component.weight.LabelsView.LabelTextProvider
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.DialogSegmentWordBinding

/**
 * Created by xyoye on 2023/6/25
 */

class SegmentWordDialog(
    activity: AppCompatActivity,
    private val words: List<String>,
    private val onSearch: (String) -> Unit
) : BaseBottomDialog<DialogSegmentWordBinding>(activity) {
    companion object {
        private val SPACE = Any()
        private val BACKSPACE = Any()
    }

    override fun getChildLayoutId() = R.layout.dialog_segment_word

    override fun initView(binding: DialogSegmentWordBinding) {
        setTitle("构建搜索词")

        setPositiveText("搜索")
        binding.emptyTv.isVisible = words.isEmpty()

        setLabels(binding)

        initListener(binding)
    }

    private fun initListener(binding: DialogSegmentWordBinding) {
        binding.candidateLabelsView.setOnLabelClickListener { label, data, _ ->
            if (data === BACKSPACE) {
                val text = binding.searchEt.text
                if (text?.isNotEmpty() == true) {
                    text.delete(Character.offsetByCodePoints(text, text.length, -1), text.length)
                }
                return@setOnLabelClickListener
            }
            val appendText =
                if (data === SPACE) {
                    " "
                } else {
                    label.text
                }
            binding.searchEt.text?.append(appendText)
        }

        binding.searchEt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(binding.searchEt)
                onSearch.invoke(
                    binding.searchEt.text
                        .toString()
                        .trim(),
                )
                dismiss()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        binding.clearTextIv.setOnClickListener {
            binding.searchEt.setText("")
        }

        setNegativeListener {
            hideKeyboard(binding.searchEt)
            dismiss()
        }

        setPositiveListener {
            hideKeyboard(binding.searchEt)
            onSearch.invoke(
                binding.searchEt.text
                    .toString()
                    .trim(),
            )
            dismiss()
        }
    }

    private fun setLabels(binding: DialogSegmentWordBinding) {
        val labelTexts =
            List(words.size + 2) {
                when (it) {
                    0 -> {
                        SPACE
                    }
                    1 -> {
                        BACKSPACE
                    }
                    else -> {
                        words[it - 2]
                    }
                }
            }

        binding.candidateLabelsView.setLabels(
            labelTexts,
            object : LabelTextProvider<Any> {
                override fun getLabelText(
                    label: TextView?,
                    position: Int,
                    data: Any?
                ): CharSequence {
                    if (data === SPACE) {
                        return "空格"
                    }
                    if (data === BACKSPACE) {
                        return "删除"
                    }
                    return data.toString()
                }
            },
        )
    }
}
