package com.xyoye.common_component.focus

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.core_ui_component.R
import com.xyoye.core_ui_component.databinding.ItemTvCategoryBinding

data class TvCategory(
    val key: String,
    val title: String,
    val create: () -> Fragment
)

class TvCategoryState(
    private val state: SavedStateHandle
) : ViewModel() {
    var activeKey: String?
        get() = state["active_key"]
        set(value) {
            state["active_key"] = value
        }
}

/** TV category controls and one active Fragment, sharing the phone page's layout slots. */
class TvCategoryPages(
    owner: ViewModelStoreOwner,
    private val fragmentManager: FragmentManager,
    tabs: TabLayout,
    pager: ViewPager2,
    private val onSelected: (Int) -> Unit = {}
) {
    val navigation = DpadRecyclerView(tabs.context)
    val content = FragmentContainerView(pager.context)
    private val state = ViewModelProvider(owner)["tv_categories_${pager.id}", TvCategoryState::class.java]
    private var categories = emptyList<TvCategory>()
    private val tagPrefix = "tv_category_${pager.id}:"
    private val adapter =
        buildAdapter {
            setupDiffUtil {
                newDataInstance { it }
                areItemsTheSame { old, new -> (old as TvCategory).key == (new as TvCategory).key }
                areContentsTheSame { old, new -> (old as TvCategory).title == (new as TvCategory).title }
            }
            addItem<TvCategory, ItemTvCategoryBinding>(R.layout.item_tv_category) {
                initView { category, position, _ ->
                    itemBinding.root.isSelected = category.key == state.activeKey
                    itemBinding.title.text = category.title
                    itemBinding.root.setOnClickListener { activate(position) }
                }
            }
        }

    val selectedIndex: Int get() = categories.indexOfFirst { it.key == state.activeKey }
    val currentFragment: Fragment? get() = fragmentManager.findFragmentByTag(tagPrefix + state.activeKey)

    init {
        replace(tabs, navigation)
        pager.adapter = null
        replace(pager, content)
        navigation.setOrientation(RecyclerView.HORIZONTAL)
        navigation.setFocusOutAllowed(false, false)
        navigation.adapter = adapter
        navigation.setOnKeyInterceptListener(
            object : DpadRecyclerView.OnKeyInterceptListener {
                override fun onInterceptKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        activate(navigation.getSelectedPosition())
                        currentFragment?.view?.requestFocus()
                    }
                    return true
                }
            }
        )
    }

    fun submit(
        items: List<TvCategory>,
        initialIndex: Int = 0
    ) {
        require(items.isNotEmpty())
        val focusedKey = categories.getOrNull(navigation.getSelectedPosition())?.key
        categories = items
        adapter.setData(items)
        val restored = selectedIndex
        activate(if (restored >= 0) restored else initialIndex.coerceIn(items.indices))
        val focusedIndex = items.indexOfFirst { it.key == focusedKey }
        navigation.setSelectedPosition(if (focusedIndex >= 0) focusedIndex else selectedIndex)
    }

    private fun activate(index: Int) {
        val category = categories.getOrNull(index) ?: return
        val previous = selectedIndex
        state.activeKey = category.key
        val tag = tagPrefix + category.key
        val fragment = fragmentManager.findFragmentByTag(tag) ?: category.create()
        if (!fragment.isAdded || fragment.isHidden) {
            val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
            fragmentManager.fragments.filter { it.tag?.startsWith(tagPrefix) == true && it !== fragment }.forEach {
                transaction.hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
            }
            if (fragment.isAdded) transaction.show(fragment) else transaction.add(content.id, fragment, tag)
            transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
        }
        if (previous >= 0) adapter.notifyItemChanged(previous)
        adapter.notifyItemChanged(index)
        onSelected(index)
    }

    fun requestNavigationFocus(): Boolean = navigation.requestFocus()

    private fun replace(
        old: View,
        replacement: View
    ) {
        val parent = old.parent as ViewGroup
        val index = parent.indexOfChild(old)
        replacement.id = old.id
        replacement.layoutParams = old.layoutParams
        parent.removeViewAt(index)
        parent.addView(replacement, index)
    }
}
