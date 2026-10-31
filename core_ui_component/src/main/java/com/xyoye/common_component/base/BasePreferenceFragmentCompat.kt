package com.xyoye.common_component.base

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceRecyclerViewAccessibilityDelegate
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.preference.TvEditTextPreferenceDialogFragmentCompat

/**
 * PreferenceFragmentCompat 的统一封装：
 * - TV/遥控器场景下，EditTextPreference 的软键盘显示需要做特殊兼容（避免无限重试导致键盘反复拉起）。
 */
abstract class BasePreferenceFragmentCompat : PreferenceFragmentCompat() {
    // Keep AndroidX's preference binding and widget behavior while placing list values on the right.
    @SuppressLint("RestrictedApi")
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        fun styleGroup(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.isIconSpaceReserved = false
                preference.icon = null
                if (preference is PreferenceGroup) styleGroup(preference)
                if (preference !is PreferenceCategory && preference !is SeekBarPreference) {
                    preference.layoutResource = com.xyoye.core_ui_component.R.layout.item_cinema_preference
                }
                if (preference is PreferenceCategory) {
                    preference.layoutResource =
                        com.xyoye.core_ui_component.R.layout.item_cinema_preference_category
                }
                if (preference is SwitchPreference) {
                    preference.widgetLayoutResource =
                        com.xyoye.core_ui_component.R.layout.cinema_preference_switch
                }
            }
        }
        styleGroup(preferenceScreen)
        return object : PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(
                holder: PreferenceViewHolder,
                position: Int
            ) {
                super.onBindViewHolder(holder, position)
                val preference = getItem(position) ?: return
                val value = holder.findViewById(com.xyoye.core_ui_component.R.id.cinema_preference_value) as? TextView ?: return
                val summary = holder.findViewById(android.R.id.summary) as? TextView
                value.text = (preference as? ListPreference)?.entry ?: if (preference.key == "app_version") preference.summary else null
                value.visibility = if (value.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (preference is ListPreference && preference.summary == preference.entry) summary?.visibility = View.GONE
                if (preference.key == "player_type") {
                    summary?.text = "用于打开新的视频"
                    summary?.visibility = View.VISIBLE
                }
                if (preference.key == "app_version") summary?.visibility = View.GONE
                holder.findViewById(com.xyoye.core_ui_component.R.id.cinema_preference_arrow)?.visibility =
                    if (preference !is SwitchPreference && preference.isSelectable) View.VISIBLE else View.GONE
                holder.isDividerAllowedAbove = true
                holder.isDividerAllowedBelow = true
            }
        }
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
