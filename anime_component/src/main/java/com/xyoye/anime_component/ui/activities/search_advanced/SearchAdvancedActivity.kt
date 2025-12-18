package com.xyoye.anime_component.ui.activities.search_advanced

import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.ActivitySearchAdvancedBinding
import com.xyoye.common_component.base.BaseActivity

class SearchAdvancedActivity : BaseActivity<SearchAdvancedViewModel, ActivitySearchAdvancedBinding>() {
    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SearchAdvancedViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_search_advanced

    override fun initView() {
    }
}
