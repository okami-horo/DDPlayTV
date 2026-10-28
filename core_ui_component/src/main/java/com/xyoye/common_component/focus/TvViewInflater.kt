package com.xyoye.common_component.focus

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatViewInflater
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.core_ui_component.R

/** Selects the platform's list container without duplicating DataBinding layouts. */
@Keep
class TvViewInflater : AppCompatViewInflater() {
    override fun createView(
        context: Context,
        name: String,
        attrs: AttributeSet
    ): View? {
        if (name == "androidx.recyclerview.widget.RecyclerView" && context.isTelevisionUiMode()) {
            val attributes = context.obtainStyledAttributes(attrs, R.styleable.TvList)
            val useDpad = attributes.getBoolean(R.styleable.TvList_tvDpadList, false)
            attributes.recycle()
            if (useDpad) return DpadRecyclerView(context, attrs).apply { itemAnimator = null }
        }
        return super.createView(context, name, attrs)
    }
}
