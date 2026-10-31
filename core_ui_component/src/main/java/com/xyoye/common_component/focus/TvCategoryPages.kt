package com.xyoye.common_component.focus

import android.view.Gravity
import android.view.KeyEvent
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

/** Remote-controlled categories and one active Fragment. */
class TvCategoryPages(
    owner: ViewModelStoreOwner,
    private val fragmentManager: FragmentManager,
    val navigation: DpadRecyclerView,
    val content: FragmentContainerView,
    private val verticalNavigation: Boolean = false,
    private val onSelected: (Int) -> Unit = {}
) {
    private val state = ViewModelProvider(owner)["tv_categories_${content.id}", TvCategoryState::class.java]
    private var categories = emptyList<TvCategory>()
    private val tagPrefix = "tv_category_${content.id}:"
    private val adapter =
        buildAdapter {
            setupDiffUtil {
                newDataInstance { it }
                areItemsTheSame { old, new -> (old as TvCategory).key == (new as TvCategory).key }
                areContentsTheSame { old, new -> (old as TvCategory).title == (new as TvCategory).title }
            }
            addItem<TvCategory, ItemTvCategoryBinding>(R.layout.item_tv_category) {
                initView { category, position, _ ->
                    if (verticalNavigation) {
                        itemBinding.root.layoutParams =
                            RecyclerView
                                .LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    (46 * navigation.resources.displayMetrics.density).toInt()
                                )
                        itemBinding.title.gravity = Gravity.CENTER_VERTICAL
                        itemBinding.title.textSize = 15f
                        val inset = (13 * navigation.resources.displayMetrics.density).toInt()
                        itemBinding.title.setPadding(inset, 0, inset, 0)
                        itemBinding.title.setBackgroundResource(R.drawable.cinema_category)
                        itemBinding.title.setTextColor(
                            androidx.core.content.ContextCompat
                                .getColorStateList(navigation.context, R.color.cinema_category_text)
                        )
                    }
                    itemBinding.root.isSelected = category.key == state.activeKey
                    itemBinding.title.text = category.title
                    itemBinding.root.setOnClickListener { activate(position) }
                }
            }
        }

    val selectedIndex: Int get() = categories.indexOfFirst { it.key == state.activeKey }
    val currentFragment: Fragment? get() = fragmentManager.findFragmentByTag(tagPrefix + state.activeKey)

    init {
        navigation.setOrientation(if (verticalNavigation) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL)
        navigation.setFocusOutAllowed(verticalNavigation, false)
        if (verticalNavigation) {
            navigation.addItemDecoration(
                com.xyoye.common_component.utils.view.ItemDecorationSpace(
                    0,
                    (
                        4 *
                            navigation.resources.displayMetrics.density
                    ).toInt()
                )
            )
        }
        navigation.adapter = adapter
        navigation.setOnKeyInterceptListener(
            object : DpadRecyclerView.OnKeyInterceptListener {
                override fun onInterceptKeyEvent(event: KeyEvent): Boolean {
                    val enterKey = if (verticalNavigation) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
                    if (event.keyCode != enterKey) return false
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
}
