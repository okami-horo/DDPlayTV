package com.xyoye.anime_component.ui.activities.search

import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.google.android.material.tabs.TabLayoutMediator
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.ActivitySearchBinding
import com.xyoye.anime_component.listener.SearchListener
import com.xyoye.anime_component.ui.fragment.search_anime.SearchAnimeFragment
import com.xyoye.anime_component.ui.fragment.search_magnet.SearchMagnetFragment
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.common_component.focus.applyDpadEditTextPolicy
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.common_component.utils.showKeyboard

@Route(path = RouteTable.Anime.Search)
class SearchActivity : BaseActivity<SearchViewModel, ActivitySearchBinding>() {
    @Autowired
    @JvmField
    var animeTitle: String? = null

    @Autowired
    @JvmField
    var searchWord: String? = null

    @Autowired
    @JvmField
    var isSearchMagnet: Boolean = false

    private lateinit var searchAdapter: SearchPageAdapter
    private var tvPages: TvCategoryPages? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SearchViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_search

    override fun initView() {
        ARouter.getInstance().inject(this)

        searchAdapter = SearchPageAdapter(searchWord)

        if (isTelevisionUiMode()) {
            tvPages =
                TvCategoryPages(this, supportFragmentManager, dataBinding.tabLayout, dataBinding.viewpager, ::updateSearchHint).apply {
                    submit(
                        listOf("anime", "magnet").mapIndexed { index, key ->
                            TvCategory(key, searchAdapter.getItemTitle(index)) { searchAdapter.createFragment(index) }
                        },
                        if (isSearchMagnet) 1 else 0
                    )
                }
            dataBinding.searchCl.nextFocusDownId = dataBinding.tabLayout.id
        } else {
            dataBinding.viewpager.adapter = searchAdapter
            dataBinding.viewpager.currentItem = if (isSearchMagnet) 1 else 0
            TabLayoutMediator(dataBinding.tabLayout, dataBinding.viewpager) { tab, position ->
                tab.text = searchAdapter.getItemTitle(position)
            }.attach()
            updateSearchHint(dataBinding.viewpager.currentItem)
        }

        if (!isSearchMagnet && dataBinding.root.isInTouchMode) {
            dataBinding.searchEt.postDelayed({
                showKeyboard(dataBinding.searchEt)
            }, 200)
        }

        if (isTelevisionUiMode() && !dataBinding.root.isInTouchMode) {
            dataBinding.searchEt.applyDpadEditTextPolicy()
            dataBinding.searchCl.requestFocus()
        }
        initListener()
    }

    private fun initListener() {
        dataBinding.backIv.setOnClickListener {
            hideKeyboard(dataBinding.searchEt)
            dataBinding.searchEt.applyDpadEditTextPolicy()
            dataBinding.searchCl.requestFocus()
            finish()
        }

        dataBinding.searchCl.setOnClickListener {
            showKeyboard(dataBinding.searchEt)
        }

        dataBinding.searchEt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(dataBinding.searchEt)
                dataBinding.searchEt.applyDpadEditTextPolicy()
                dataBinding.searchCl.requestFocus()
                search(dataBinding.searchEt.text.toString())
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        dataBinding.searchEt.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(editable: Editable?) {
                    val textLength = editable?.length ?: 0
                    if (textLength > 0) {
                        if (dataBinding.searchEt.isFocused) {
                            dataBinding.clearTextIv.isVisible = true
                        }
                    } else {
                        dataBinding.clearTextIv.isVisible = false
                        clearText()
                    }
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                    // No-op: this page only reacts to finalized text in afterTextChanged.
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    // No-op: we update UI state after text is fully applied.
                }
            },
        )

        dataBinding.searchEt.setOnFocusChangeListener { _, isFocus ->
            val searchText = dataBinding.searchEt.text?.toString() ?: ""
            dataBinding.clearTextIv.isVisible = isFocus && searchText.isNotEmpty()
        }

        dataBinding.searchTv.setOnClickListener {
            hideKeyboard(dataBinding.searchEt)
            dataBinding.searchEt.applyDpadEditTextPolicy()
            dataBinding.searchCl.requestFocus()
            search(dataBinding.searchEt.text.toString())
        }

        dataBinding.clearTextIv.setOnClickListener {
            viewModel.searchText.set("")
            showKeyboard(dataBinding.searchEt)
            clearText()
        }

        dataBinding.viewpager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateSearchHint(position)
                }
            },
        )
    }

    private fun search(searchText: String) {
        getCurrentSearchFragment()?.search(searchText)
    }

    private fun clearText() {
        getCurrentSearchFragment()?.onTextClear()
    }

    private fun getCurrentSearchFragment(): SearchListener? {
        tvPages?.let { return it.currentFragment as? SearchListener }
        val currentItem = dataBinding.viewpager.currentItem
        val fragmentTag = searchAdapter.getFragmentTag(currentItem)
        return supportFragmentManager.findFragmentByTag(fragmentTag) as? SearchListener
    }

    private fun updateSearchHint(position: Int) {
        dataBinding.searchEt.hint =
            when (position) {
                0 -> getString(R.string.search_anime_hint)
                1 -> getString(R.string.search_magnet_hint)
                else -> ""
            }
    }

    fun onSearch(searchText: String) {
        hideKeyboard(dataBinding.searchEt)
        dataBinding.searchEt.applyDpadEditTextPolicy()
        dataBinding.searchCl.requestFocus()
        viewModel.searchText.set(searchText)
    }

    fun hideSearchKeyboard() {
        hideKeyboard(dataBinding.searchEt)
        dataBinding.searchEt.applyDpadEditTextPolicy()
        dataBinding.searchCl.requestFocus()
    }

    inner class SearchPageAdapter(
        private val searchWord: String?
    ) : FragmentStateAdapter(this@SearchActivity) {
        private val titles = arrayOf("搜番剧", "搜资源")

        override fun getItemCount(): Int = titles.size

        override fun createFragment(position: Int): Fragment =
            when (position) {
                0 -> SearchAnimeFragment.newInstance()
                1 -> SearchMagnetFragment.newInstance(searchWord)
                else -> throw IndexOutOfBoundsException("only 2 fragment, but position : $position")
            }

        fun getItemTitle(position: Int): String = titles.getOrNull(position).orEmpty()

        fun getFragmentTag(position: Int): String = "f${getItemId(position)}"
    }
}
