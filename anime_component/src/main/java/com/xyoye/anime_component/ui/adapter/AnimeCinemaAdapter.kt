package com.xyoye.anime_component.ui.adapter

import android.app.Activity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.ItemAnimeCinemaBinding
import com.xyoye.anime_component.utils.loadAnimeCover
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.session.UserSessionManager
import com.xyoye.common_component.utils.FastClickFilter
import com.xyoye.data_component.bean.AnimeArgument
import com.xyoye.data_component.data.AnimeData

object AnimeCinemaAdapter {
    fun create(
        activity: Activity,
        showStatus: Boolean = false
    ) = buildAdapter {
        setupDiffUtil {
            areItemsTheSame { old, new -> old is AnimeData && new is AnimeData && old.animeId == new.animeId }
        }
        addItem<AnimeData, ItemAnimeCinemaBinding>(R.layout.item_anime_cinema) {
            initView { data, _, _ ->
                bind(activity, itemBinding, data, showStatus)
            }
        }
    }

    fun bind(
        activity: Activity,
        binding: ItemAnimeCinemaBinding,
        data: AnimeData,
        showStatus: Boolean
    ) {
        binding.apply {
            coverIv.loadAnimeCover(data.imageUrl)
            animeNameTv.text = data.animeTitle
            followTagView.isVisible = UserSessionManager.isLoggedIn() && data.isFavorited
            statusTv.isVisible = showStatus
            statusTv.text = if (data.isOnAir) "连载中" else "已完结"
            itemLayout.setOnClickListener {
                if (FastClickFilter.isNeedFilter()) return@setOnClickListener
                ViewCompat.setTransitionName(coverIv, "cover_image")
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(activity, coverIv, coverIv.transitionName)
                ARouter
                    .getInstance()
                    .build(RouteTable.Anime.AnimeDetail)
                    .withParcelable("animeArgument", AnimeArgument.fromData(data))
                    .withOptionsCompat(options)
                    .navigation(activity)
            }
        }
    }
}
