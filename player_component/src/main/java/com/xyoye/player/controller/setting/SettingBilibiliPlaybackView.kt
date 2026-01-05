package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.bilibili.BilibiliPlayMode
import com.xyoye.common_component.bilibili.BilibiliQuality
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSessionStore
import com.xyoye.common_component.extension.nextItemIndex
import com.xyoye.common_component.extension.previousItemIndex
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemPlayerSettingTypeBinding
import com.xyoye.player_component.databinding.ItemSettingTracksBinding
import com.xyoye.player_component.databinding.LayoutSettingBilibiliPlaybackBinding

class SettingBilibiliPlaybackView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : BaseSettingView<LayoutSettingBilibiliPlaybackBinding>(context, attrs, defStyleAttr) {
        private data class Section(
            val title: String,
        )

        private data class Option(
            val label: String,
            val update: BilibiliPlaybackSession.PreferenceUpdate,
            val selected: Boolean,
        )

        private var rows: List<Any> = emptyList()
        private var updateBlock: ((BilibiliPlaybackSession.PreferenceUpdate) -> Unit)? = null

        private val decoration
            get() =
                ItemDecorationOrientation(
                    dividerPx = dp2px(10),
                    headerFooterPx = 0,
                    orientation = RecyclerView.VERTICAL,
                )

        init {
            initView()
        }

        override fun getLayoutId(): Int = R.layout.layout_setting_bilibili_playback

        override fun getSettingViewType(): SettingViewType = SettingViewType.BILIBILI_PLAYBACK

        override fun onViewShow() {
            viewBinding.tvTitle.text = "B站画质/编码"
            refreshRows()
        }

        override fun onViewHide() {
            viewBinding.rvOptions.focusedChild?.clearFocus()
            viewBinding.rvOptions.clearFocus()
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent?
        ): Boolean {
            if (isSettingShowing().not()) {
                return false
            }

            val handled = handleKeyCode(keyCode)
            if (handled) {
                return true
            }

            val firstIndex = rows.indexOfFirst { it is Option }
            if (firstIndex != -1) {
                viewBinding.rvOptions.requestIndexChildFocus(firstIndex)
            }
            return true
        }

        fun setUpdateBlock(block: (BilibiliPlaybackSession.PreferenceUpdate) -> Unit) {
            updateBlock = block
        }

        private fun initView() {
            viewBinding.rvOptions.apply {
                itemAnimator = null
                layoutManager = vertical()
                adapter =
                    buildAdapter {
                        addItem<Any, ItemPlayerSettingTypeBinding>(R.layout.item_player_setting_type) {
                            checkType { data, _ -> data is Section }
                            initView { data, _, _ ->
                                itemBinding.tvType.text = (data as Section).title
                            }
                        }

                        addItem<Any, ItemSettingTracksBinding>(R.layout.item_setting_tracks) {
                            checkType { data, _ -> data is Option }
                            initView { data, _, _ ->
                                val option = data as Option
                                itemBinding.tvName.text = option.label
                                itemBinding.tvName.isSelected = option.selected
                                itemBinding.tvName.setOnClickListener {
                                    if (!option.selected) {
                                        updateBlock?.invoke(option.update)
                                        onSettingVisibilityChanged(false)
                                    }
                                }
                            }
                        }
                    }
                addItemDecoration(decoration)
            }
        }

        private fun refreshRows() {
            val source = mControlWrapper.getVideoSource()
            val session = BilibiliPlaybackSessionStore.get(source.getStorageId(), source.getUniqueKey())
            val snapshot = session?.snapshot()

            rows =
                if (snapshot == null) {
                    emptyList()
                } else {
                    buildRows(snapshot)
                }
            viewBinding.rvOptions.setData(rows)
            viewBinding.tvEmpty.isVisible = rows.none { it is Option }
        }

        private fun buildRows(snapshot: BilibiliPlaybackSession.Snapshot): List<Any> {
            val items = mutableListOf<Any>()

            items.add(Section("播放模式"))
            BilibiliPlayMode.entries.forEach { mode ->
                items.add(
                    Option(
                        label = mode.label,
                        update = BilibiliPlaybackSession.PreferenceUpdate(playMode = mode),
                        selected = snapshot.playMode == mode,
                    ),
                )
            }

            if (snapshot.dashAvailable) {
                items.add(Section("画质"))
                snapshot.qualities
                    .sorted()
                    .forEach { qn ->
                        items.add(
                            Option(
                                label = qualityLabel(qn),
                                update = BilibiliPlaybackSession.PreferenceUpdate(qualityQn = qn),
                                selected = snapshot.selectedQualityQn == qn,
                            ),
                        )
                    }

                items.add(Section("视频编码"))
                snapshot.videoCodecs.forEach { codec ->
                    items.add(
                        Option(
                            label = codec.label,
                            update = BilibiliPlaybackSession.PreferenceUpdate(videoCodec = codec),
                            selected = snapshot.selectedVideoCodec == codec,
                        ),
                    )
                }

                items.add(Section("音质"))
                snapshot.audios.forEach { audio ->
                    val kbps = if (audio.bandwidth > 0) audio.bandwidth / 1000 else 0
                    val label = if (kbps > 0) "音质 ${audio.id}（${kbps}kbps）" else "音质 ${audio.id}"
                    items.add(
                        Option(
                            label = label,
                            update = BilibiliPlaybackSession.PreferenceUpdate(audioQualityId = audio.id),
                            selected = snapshot.selectedAudioQualityId == audio.id,
                        ),
                    )
                }
            }

            return items
        }

        private fun qualityLabel(qn: Int): String {
            val mapped = BilibiliQuality.fromQn(qn)
            if (mapped != BilibiliQuality.AUTO) {
                return mapped.label
            }
            if (qn == BilibiliQuality.AUTO.qn) {
                return mapped.label
            }
            return "QN $qn"
        }

        private fun handleKeyCode(keyCode: Int): Boolean {
            val focusedView =
                viewBinding.rvOptions.focusedChild
                    ?: return false
            val focusedIndex = viewBinding.rvOptions.getChildAdapterPosition(focusedView)
            if (focusedIndex == -1) {
                return false
            }

            val targetIndex =
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> rows.previousItemIndex<Option>(focusedIndex)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> rows.nextItemIndex<Option>(focusedIndex)
                    else -> return false
                }
            if (targetIndex == -1) {
                return false
            }
            viewBinding.rvOptions.requestIndexChildFocus(targetIndex)
            return true
        }
    }
