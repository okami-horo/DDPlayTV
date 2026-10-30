package com.xyoye.common_component.weight

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isGone
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.core_ui_component.R
import com.xyoye.core_ui_component.databinding.DialogBottomActionBinding
import com.xyoye.core_ui_component.databinding.ItemBottomActionVerticalBinding
import com.xyoye.data_component.bean.SheetActionBean

/**
 * Created by xyoye on 2020/11/18.
 */

class BottomActionDialog(
    activity: Activity,
    private val actionData: List<SheetActionBean>,
    private val title: String? = null,
    private val callback: (SheetActionBean) -> Boolean
) : BaseBottomDialog<DialogBottomActionBinding>(activity) {
    var onNegativeCallback: (() -> Unit)? = null

    override fun getChildLayoutId() = R.layout.dialog_bottom_action

    override fun initView(binding: DialogBottomActionBinding) {
        setTitle(title ?: "请选择操作")

        setNegativeListener {
            onNegativeCallback?.invoke()
            dismiss()
        }
        setPositiveVisible(false)

        removeParentPadding()

        binding.contentRv.apply {
            layoutManager = vertical()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new ->
                            (old as SheetActionBean).actionId == (new as SheetActionBean).actionId
                        }
                    }
                    addItem<SheetActionBean, ItemBottomActionVerticalBinding>(R.layout.item_bottom_action_vertical) {
                        initView { data, _, _ ->
                            itemBinding.apply {
                                actionNameTv.text = data.actionName
                                if (data.actionIconRes != -1) {
                                    actionIv.setImageResource(data.actionIconRes)
                                }
                                actionDescribeTv.isGone = data.describe.isNullOrEmpty()
                                actionDescribeTv.text = data.describe
                                itemLayout.setOnClickListener {
                                    if (callback.invoke(data)) dismiss()
                                }
                            }
                        }
                    }
                }

            setData(actionData)

            // DpadRecyclerView treats wrap_content as the parent's size. A scroll container
            // measures it with UNSPECIFIED height, which otherwise produces a zero viewport.
            fun updateViewport(width: Int) {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                rootViewBinding.bottomActionLl.measure(widthSpec, unspecified)
                val limit =
                    (resources.displayMetrics.heightPixels * 0.85f).toInt() -
                        rootViewBinding.titleTv.layoutParams.height - rootViewBinding.bottomActionLl.measuredHeight
                val row = ItemBottomActionVerticalBinding.inflate(LayoutInflater.from(context), this, false)
                var contentHeight = 0
                for (action in actionData) {
                    row.actionNameTv.text = action.actionName
                    row.actionDescribeTv.isGone = action.describe.isNullOrEmpty()
                    row.actionDescribeTv.text = action.describe
                    row.root.measure(widthSpec, unspecified)
                    contentHeight += row.root.measuredHeight
                    if (contentHeight >= limit) break
                }
                val height = contentHeight.coerceAtMost(limit.coerceAtLeast(1))
                if (layoutParams.height != height) {
                    layoutParams = layoutParams.apply { this.height = height }
                }
            }
            updateViewport(resources.displayMetrics.widthPixels)
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) updateViewport(right - left)
            }
        }
    }
}
