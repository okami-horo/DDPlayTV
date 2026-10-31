package com.xyoye.common_component.base

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceRecyclerViewAccessibilityDelegate
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.preference.TvEditTextPreferenceDialogFragmentCompat

/**
 * PreferenceFragmentCompat 的统一封装：
 * - TV/遥控器场景下，EditTextPreference 的软键盘显示需要做特殊兼容（避免无限重试导致键盘反复拉起）。
 */
abstract class BasePreferenceFragmentCompat : PreferenceFragmentCompat() {
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        fun styleGroup(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                if (preference is PreferenceGroup) styleGroup(preference)
                if (preference !is PreferenceCategory && preference !is SeekBarPreference) {
                    preference.layoutResource = com.xyoye.core_ui_component.R.layout.item_cinema_preference
                }
            }
        }
        styleGroup(preferenceScreen)
        return super.onCreateAdapter(preferenceScreen)
    }

    // Preserve the delegate installed by PreferenceFragmentCompat when replacing its list.
    @SuppressLint("RestrictedApi")
    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView =
        DpadRecyclerView(requireContext()).apply {
            id = androidx.preference.R.id.recycler_view
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setFocusOutAllowed(true, false)
            setAccessibilityDelegateCompat(PreferenceRecyclerViewAccessibilityDelegate(this))
        }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is EditTextPreference) {
            val fragmentManager = parentFragmentManager
            if (fragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) return

            val dialogFragment =
                TvEditTextPreferenceDialogFragmentCompat.newInstance(preference.key)

            @Suppress("DEPRECATION")
            dialogFragment.setTargetFragment(this, 0)

            dialogFragment.show(fragmentManager, DIALOG_FRAGMENT_TAG)
            return
        }

        super.onDisplayPreferenceDialog(preference)
    }

    private companion object {
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"
    }
}
