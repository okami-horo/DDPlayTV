package com.okamihoro.ddplaytv.ui.tv

import android.view.View
import androidx.fragment.app.Fragment
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.focus.DpadFocusOwner

class TvShellFocusCoordinator(
    private val navRecyclerView: DpadRecyclerView,
    private val selectedNavIndexProvider: () -> Int,
    private val currentFragmentProvider: () -> Fragment?,
) {
    fun navigationTarget(): View {
        val index = selectedNavIndexProvider()
        navRecyclerView.setSelectedPosition(index)
        return navRecyclerView.findViewHolderForAdapterPosition(index)?.itemView ?: navRecyclerView
    }

    fun requestNavFocus(): Boolean = navigationTarget().requestFocus()

    fun requestContentFocus(): Boolean = (currentFragmentProvider() as? DpadFocusOwner)?.requestDefaultDpadFocus() == true
}
