package com.xyoye.anime_component.ui.activities.search

import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.ActivitySearchBinding
import com.xyoye.anime_component.listener.SearchListener
import com.xyoye.anime_component.ui.fragment.search_anime.SearchAnimeFragment
import com.xyoye.anime_component.ui.fragment.search_magnet.SearchMagnetFragment
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
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

    private var tvPages: TvCategoryPages? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SearchViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_search

    override fun initView() {
        ARouter.getInstance().inject(this)

        tvPages =
            TvCategoryPages(
                this,
                supportFragmentManager,
                dataBinding.tabLayout,
                dataBinding.viewpager,
                onSelected = ::updateSearchHint
            ).apply {
                submit(
                    listOf(
                        TvCategory("anime", "搜番剧") { SearchAnimeFragment.newInstance() },
                        TvCategory("magnet", "搜资源") { SearchMagnetFragment.newInstance(searchWord) }
                    ),
                    if (isSearchMagnet) 1 else 0
                )
            }
        dataBinding.searchCl.nextFocusDownId = dataBinding.tabLayout.id

        if (!dataBinding.root.isInTouchMode) {
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
    }

    private fun search(searchText: String) {
        getCurrentSearchFragment()?.search(searchText)
    }

    private fun clearText() {
        getCurrentSearchFragment()?.onTextClear()
    }

    private fun getCurrentSearchFragment(): SearchListener? = tvPages?.currentFragment as? SearchListener

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
}
