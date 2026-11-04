package com.xyoye.player.controller.setting

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.utils.dp2px
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemSubtitleQuickBinding
import com.xyoye.player_component.databinding.LayoutSubtitleQuickBinding

class SubtitleQuickView(
    context: Context
) : BaseSettingView<LayoutSubtitleQuickBinding>(context) {
    private var tracks = emptyList<VideoTrackBean>()
    private var focusIndex = 0
    private val rowHeight = (dp2px(48) * resources.configuration.fontScale).toInt()
    private val rowGap = dp2px(2.667f)
    private val optionsAdapter = OptionsAdapter()

    init {
        viewBinding.subtitleQuickList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = optionsAdapter
            itemAnimator = null
            addItemDecoration(
                object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: android.graphics.Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        if (parent.getChildAdapterPosition(view) > 0) outRect.top = rowGap
                    }
                }
            )
        }
    }

    override fun getLayoutId() = R.layout.layout_subtitle_quick

    override fun getSettingViewType() = SettingViewType.SUBTITLE_QUICK

    override fun getGravity() = Gravity.CENTER

    override fun onViewShow() {
        refreshTracks(false)
    }

    override fun onViewShowed() {
        if (isInTouchMode || !isSettingShowing()) return
        val list = viewBinding.subtitleQuickList
        list.scrollToPosition(focusIndex)
        list.doOnNextLayout {
            if (isSettingShowing()) list.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus()
        }
        list.requestLayout()
    }

    override fun onViewHide() {
        viewBinding.subtitleQuickList.clearFocus()
    }

    override fun onTrackChanged(type: TrackType) {
        if (type == TrackType.SUBTITLE && isSettingShowing()) refreshTracks(true)
    }

    private fun refreshTracks(keepFocus: Boolean) {
        val focused = viewBinding.subtitleQuickList.focusedChild?.tag as? VideoTrackBean
        val available = mControlWrapper.getTracks(TrackType.SUBTITLE).filterNot { it.disable }
        val visible = mControlWrapper.isUserSubtitleVisible() && available.any { it.selected }
        tracks = listOf(VideoTrackBean.disable(TrackType.SUBTITLE, !visible).copy(name = "关")) +
            available.map { it.copy(selected = visible && it.selected) }
        focusIndex =
            if (keepFocus && focused != null) {
                tracks.indexOfFirst { it.id == focused.id && it.trackResource == focused.trackResource && it.name == focused.name }
            } else {
                tracks.indexOfFirst { it.selected }
            }.coerceAtLeast(0)
        viewBinding.subtitleQuickMessage.isVisible = available.isEmpty()
        viewBinding.subtitleQuickList.layoutParams =
            viewBinding.subtitleQuickList.layoutParams.apply {
                height = minOf(dp2px(200), tracks.size * rowHeight + (tracks.size - 1) * rowGap)
            }
        optionsAdapter.notifyDataSetChanged()
        if (keepFocus) onViewShowed()
    }

    private fun choose(track: VideoTrackBean) {
        if (!isSettingShowing()) return
        if (track.disable) {
            mControlWrapper.setUserSubtitleVisible(false)
        } else {
            mControlWrapper.selectTrack(track)
            mControlWrapper.setUserSubtitleVisible(true)
        }
        mControlWrapper.setTrackUpdated(TrackType.SUBTITLE)
        mControlWrapper.hideSettingView()
    }

    private inner class OptionsAdapter : RecyclerView.Adapter<OptionHolder>() {
        override fun getItemCount() = tracks.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ) = OptionHolder(ItemSubtitleQuickBinding.inflate(LayoutInflater.from(context), parent, false))

        override fun onBindViewHolder(
            holder: OptionHolder,
            position: Int
        ) {
            holder.bind(tracks[position])
        }

        override fun onViewRecycled(holder: OptionHolder) {
            holder.updateFocus(false)
        }
    }

    private inner class OptionHolder(
        val binding: ItemSubtitleQuickBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val startMarquee =
            Runnable {
                if (binding.root.hasFocus()) binding.subtitleQuickName.ellipsize = TextUtils.TruncateAt.MARQUEE
            }

        fun bind(track: VideoTrackBean) {
            binding.root.apply {
                layoutParams.height = rowHeight
                tag = track
                isActivated = track.selected
                contentDescription = track.name + if (track.selected) "，当前字幕" else ""
                setOnClickListener { choose(track) }
                setOnFocusChangeListener { _, focused -> updateFocus(focused) }
            }
            binding.subtitleQuickName.text = track.name
            binding.subtitleQuickCheck.visibility = if (track.selected) View.VISIBLE else View.INVISIBLE
            updateFocus(binding.root.hasFocus())
        }

        fun updateFocus(focused: Boolean) {
            val color = ContextCompat.getColor(context, if (focused) R.color.cinema_background else R.color.cinema_text)
            binding.subtitleQuickName.apply {
                removeCallbacks(startMarquee)
                ellipsize = TextUtils.TruncateAt.END
                isSelected = focused
                setTextColor(color)
                if (focused) postDelayed(startMarquee, 1000)
            }
            binding.subtitleQuickCheck.imageTintList = ColorStateList.valueOf(color)
        }
    }
}
