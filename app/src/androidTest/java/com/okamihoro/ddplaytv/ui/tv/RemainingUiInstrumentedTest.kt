package com.okamihoro.ddplaytv.ui.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.playback.withIndexedPlaybackFile
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.anime_component.ui.activities.AnimeCollectionState
import com.xyoye.anime_component.ui.activities.anime_detail.AnimeDetailActivity
import com.xyoye.anime_component.ui.activities.anime_detail.AnimeDetailViewModel
import com.xyoye.anime_component.ui.activities.anime_follow.AnimeFollowActivity
import com.xyoye.anime_component.ui.activities.anime_history.AnimeHistoryActivity
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.utils.meida.VideoExtension
import com.xyoye.common_component.weight.LabelsView
import com.xyoye.data_component.bean.AnimeArgument
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.BangumiData
import com.xyoye.data_component.data.CloudHistoryListData
import com.xyoye.data_component.data.EpisodeData
import com.xyoye.data_component.data.FollowAnimeData
import com.xyoye.data_component.data.TagData
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.ui.dialog.SegmentWordDialog
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.video.LoadingView
import com.xyoye.player.controller.video.MessageContainer
import com.xyoye.player.utils.MessageTime
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.storage_component.ui.dialog.FontCacheProgressDialog
import com.xyoye.user_component.ui.activities.about_us.AboutUsActivity
import com.xyoye.user_component.ui.dialog.UserCoverDialog
import com.xyoye.user_component.ui.dialog.VideoExtensionSupportSettingDialog
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.anime_component.R as AnimeR
import com.xyoye.core_ui_component.R as CoreR
import com.xyoye.local_component.R as LocalR
import com.xyoye.player_component.R as PlayerR
import com.xyoye.storage_component.R as StorageR
import com.xyoye.user_component.R as UserR

@RunWith(AndroidJUnit4::class)
class RemainingUiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @Test fun wordBuilderKeepsChipFocusDeletesCodePointAndSearchesOnce() {
        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            lateinit var dialog: SegmentWordDialog
            val searches = mutableListOf<String>()
            scenario.onActivity { dialog = SegmentWordDialog(it, listOf("药屋少女", "的呢喃", "🌙"), searches::add).apply { show() } }
            settle()
            scenario.onActivity { dialog.findViewById<View>(LocalR.id.clear_text_iv)!!.requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { assertTrue(dialog.currentFocus is TextView) }
            scenario.onActivity { dialog.findViewById<LabelsView>(LocalR.id.candidate_labels_view)!!.getChildAt(2).requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity {
                assertEquals("药屋少女", dialog.findViewById<EditText>(LocalR.id.search_et)!!.text.toString())
                assertEquals("药屋少女", (dialog.currentFocus as TextView).text.toString())
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            screenshot("segment-normal")
            scenario.onActivity {
                dialog.findViewById<EditText>(LocalR.id.search_et)!!.setText("药屋🌙")
                dialog.findViewById<LabelsView>(LocalR.id.candidate_labels_view)!!.getChildAt(1).requestFocus()
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity {
                assertEquals("药屋", dialog.findViewById<EditText>(LocalR.id.search_et)!!.text.toString())
                dialog.findViewById<View>(CoreR.id.positive_bt)!!.requestFocus()
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf("药屋"), searches)
            assertFalse(dialog.isShowing)
            scenario.onActivity { dialog = SegmentWordDialog(it, emptyList(), searches::add).apply { show() } }
            screenshot("segment-empty")
            key(KeyEvent.KEYCODE_BACK)
            scenario.onActivity { dialog = SegmentWordDialog(it, List(40) { n -> "候选词$n" }, searches::add).apply { show() } }
            settle()
            scenario.onActivity { dialog.findViewById<LabelsView>(LocalR.id.candidate_labels_view)!!.getChildAt(2).requestFocus() }
            repeat(12) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            scenario.onActivity {
                assertFullyVisible(dialog.findViewById(CoreR.id.positive_bt)!!)
                assertFullyVisible(dialog.currentFocus!!)
            }
            screenshot("segment-long")
            key(KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun extensionsValidateResetAndPersistAndAvatarSelectsWithRemote() {
        val previous = VideoExtension.supportText
        try {
            ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
                lateinit var dialog: VideoExtensionSupportSettingDialog
                scenario.onActivity { dialog = VideoExtensionSupportSettingDialog(it).apply { show() } }
                screenshot("extensions-normal")
                scenario.onActivity {
                    dialog.findViewById<EditText>(UserR.id.et_video_extension)!!.setText("")
                    dialog.findViewById<View>(CoreR.id.positive_bt)!!.requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertTrue(dialog.findViewById<View>(UserR.id.error_tv)!!.isShown)
                    assertTrue(dialog.isShowing)
                }
                screenshot("extensions-error")
                scenario.onActivity { dialog.findViewById<View>(UserR.id.tv_reset_extension)!!.requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(VideoExtension.supportText, dialog.findViewById<EditText>(UserR.id.et_video_extension)!!.text.toString())
                }
                scenario.onActivity {
                    dialog.findViewById<EditText>(UserR.id.et_video_extension)!!.setText("mkv,mp4")
                    dialog.findViewById<View>(CoreR.id.positive_bt)!!.requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals("mkv,mp4", VideoExtension.supportText)
                scenario.recreate()
                scenario.onActivity { dialog = VideoExtensionSupportSettingDialog(it).apply { show() } }
                scenario.onActivity {
                    assertEquals(
                        "mkv,mp4",
                        dialog.findViewById<EditText>(UserR.id.et_video_extension)!!.text.toString()
                    )
                }
                key(KeyEvent.KEYCODE_BACK)
                lateinit var avatar: UserCoverDialog
                var selected = -1
                scenario.onActivity { avatar = UserCoverDialog(it) { selected = it }.apply { show() } }
                settle()
                scenario.onActivity { avatar.findViewById<DpadRecyclerView>(UserR.id.user_cover_rv)!!.setSelectedPosition(0) }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                screenshot("avatar-focus")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals(1, selected)
                assertFalse(avatar.isShowing)
                scenario.onActivity { avatar = UserCoverDialog(it) { selected = it }.apply { show() } }
                repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                screenshot("avatar-scroll")
                key(KeyEvent.KEYCODE_BACK)
            }
        } finally {
            VideoExtension.update(previous)
        }
    }

    @Test fun fontProgressClampsAndCannotBeCancelledByBack() {
        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            lateinit var dialog: FontCacheProgressDialog
            scenario.onActivity {
                dialog =
                    FontCacheProgressDialog(it).apply {
                        show()
                        update(-1, 8)
                    }
            }
            screenshot("font-preparing")
            scenario.onActivity {
                assertEquals("0%", dialog.findViewById<TextView>(StorageR.id.progress_percent_tv)!!.text.toString())
                dialog.update(12, 5)
            }
            screenshot("font-progress")
            key(KeyEvent.KEYCODE_BACK)
            scenario.onActivity {
                assertTrue(dialog.isShowing)
                dialog.update(12, 99)
                assertEquals("100%", dialog.findViewById<TextView>(StorageR.id.progress_percent_tv)!!.text.toString())
                dialog.dismiss()
            }
        }
    }

    @Test fun collectionsKeepInjectedDataAcrossSearchAndRecreationAndRenderFeedback() {
        val entries =
            MutableList(12) {
                AnimeData(
                    animeId = it + 1,
                    animeTitle =
                        if (it ==
                            4
                        ) {
                            "长标题示例：在故事与日常之间寻找每一处细微的线索"
                        } else {
                            "本地界面夹具 ${it + 1}"
                        }
                )
            }
        val launches =
            listOf(
                Intent(context, AnimeFollowActivity::class.java).putExtra("followData", FollowAnimeData(entries)),
                Intent(context, AnimeHistoryActivity::class.java).putExtra("historyData", CloudHistoryListData(entries))
            )
        launches.forEachIndexed { index, intent ->
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                val listId = if (index == 0) AnimeR.id.follow_rv else AnimeR.id.history_rv
                settle()
                scenario.onActivity { it.findViewById<View>(listId).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                screenshot("collection-$index-normal")
                scenario.onActivity { assertEquals(12, (it.findViewById<DpadRecyclerView>(listId).adapter as BaseAdapter).items.size) }
                scenario.recreate()
                settle()
                scenario.onActivity {
                    assertEquals(12, (it.findViewById<DpadRecyclerView>(listId).adapter as BaseAdapter).items.size)
                    it.findViewById<EditText>(AnimeR.id.search_et).setText("absent")
                }
                screenshot("collection-$index-empty")
                scenario.onActivity {
                    assertTrue(it.findViewById<View>(AnimeR.id.empty_tv).isShown)
                    it.findViewById<View>(AnimeR.id.clear_bt).requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                scenario.onActivity { assertTrue(it.findViewById<View>(listId).isShown) }
                // Visual state fixtures; actual refresh ownership is covered by the JVM tests.
                for (loading in listOf(true, false)) {
                    scenario.onActivity {
                        val vm =
                            if (index ==
                                0
                            ) {
                                (it as AnimeFollowActivity).getOwnerViewModel()
                            } else {
                                (it as AnimeHistoryActivity).getOwnerViewModel()
                            }
                        val field = vm.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }

                        @Suppress("UNCHECKED_CAST")
                        val state = field.get(vm) as MutableStateFlow<AnimeCollectionState>
                        state.value = AnimeCollectionState(loading = loading, error = if (loading) null else "本地失败状态夹具")
                    }
                    screenshot("collection-$index-${if (loading) "loading" else "error"}")
                }
                key(KeyEvent.KEYCODE_BACK)
            }
        }
    }

    @Test fun detailChildrenScrollSortMarkAndReturnUsingRemote() {
        ActivityScenario
            .launch<AnimeDetailActivity>(
                Intent(context, AnimeDetailActivity::class.java).putExtra("animeArgument", AnimeArgument(id = -1, title = "药屋少女的呢喃"))
            ).use { scenario ->
                lateinit var model: AnimeDetailViewModel
                val fixture = bangumi()
                scenario.onActivity {
                    model = it.getOwnerViewModel() as AnimeDetailViewModel
                    // Keep this a local layout fixture; do not depend on the detail service.
                    model.viewModelScope.coroutineContext.cancelChildren()
                    model.animeTitleField.set(fixture.animeTitle)
                    model.animeStatusField.set("状态：已完结")
                    model.animeTypeField.set("类型：TV")
                    model.animeRateField.set("评分：9.1")
                    model.animeDetailLiveData.value = fixture
                }
                SystemClock.sleep(3500)
                settle()
                scenario.onActivity {
                    val tabs = it.findViewById<DpadRecyclerView>(AnimeR.id.tab_layout)
                    repeat(tabs.childCount) { index -> assertMeasuredBounds(tabs.getChildAt(index)) }
                    val tags = it.findViewById<DpadRecyclerView>(AnimeR.id.tag_rv)
                    repeat(tags.childCount) { index -> assertMeasuredBounds(tags.getChildAt(index)) }
                }
                screenshot("detail-intro")
                scenario.onActivity { it.findViewById<View>(AnimeR.id.anime_info_expend_ll).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                repeat(10) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                screenshot("detail-intro-long")
                scenario.onActivity {
                    it.findViewById<DpadRecyclerView>(AnimeR.id.tab_layout).apply {
                        requestFocus()
                        setSelectedPosition(1)
                    }
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                settle()
                scenario.onActivity {
                    val list = it.findViewById<DpadRecyclerView>(AnimeR.id.episode_rv)
                    list.requestFocus()
                }
                settle()
                scenario.onActivity {
                    val list = it.findViewById<DpadRecyclerView>(AnimeR.id.episode_rv)
                    assertMeasuredBounds(requireNotNull(list.findContainingItemView(requireNotNull(it.currentFocus))))
                }
                screenshot("detail-episodes")
                scenario.onActivity { it.findViewById<View>(AnimeR.id.sort_cl).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                scenario.onActivity {
                    val list = it.findViewById<DpadRecyclerView>(AnimeR.id.episode_rv)
                    assertEquals("24", ((list.adapter as BaseAdapter).items.first() as EpisodeData).episodeId)
                    it.findViewById<View>(AnimeR.id.mark_mode_bt).requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(AnimeR.id.tv_set_read, it.currentFocus?.id)
                    it.findViewById<View>(AnimeR.id.tv_mark_all).requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                scenario.onActivity {
                    val episodes =
                        (
                            it
                                .findViewById<DpadRecyclerView>(
                                    AnimeR.id.episode_rv
                                ).adapter as BaseAdapter
                        ).items.filterIsInstance<EpisodeData>()
                    assertTrue(episodes.all { episode -> episode.isMarked })
                }
                screenshot("detail-episodes-mark")
                key(KeyEvent.KEYCODE_BACK)
                scenario.onActivity { assertEquals(AnimeR.id.mark_mode_bt, it.currentFocus?.id) }
                scenario.onActivity {
                    it.findViewById<DpadRecyclerView>(AnimeR.id.tab_layout).apply {
                        requestFocus()
                        setSelectedPosition(2)
                    }
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                screenshot("detail-recommend")
                repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                scenario.onActivity {
                    val list = it.findViewById<DpadRecyclerView>(AnimeR.id.recommend_rv)
                    val card = requireNotNull(list.findContainingItemView(requireNotNull(it.currentFocus)))
                    assertTrue("Remote must reach the final recommendation row", list.getChildAdapterPosition(card) >= 14)
                    assertTrue(card.findViewById<TextView>(AnimeR.id.anime_name_tv).text.startsWith("推荐作品"))
                    assertTrue(card.findViewById<View>(AnimeR.id.status_tv).isShown)
                    assertFullyVisible(card)
                    assertMeasuredBounds(card)
                }
                screenshot("detail-recommend-scroll")
                var reachedTabs = false
                repeat(7) {
                    if (!reachedTabs) {
                        key(KeyEvent.KEYCODE_DPAD_UP)
                        scenario.onActivity { reachedTabs = it.findViewById<View>(AnimeR.id.tab_layout).hasFocus() }
                    }
                }
                assertTrue("Remote must return from recommendations to categories", reachedTabs)
                scenario.onActivity {
                    model.animeDetailLiveData.value = fixture.copy(episodes = mutableListOf(), summary = null, metadata = null)
                    it.findViewById<DpadRecyclerView>(AnimeR.id.tab_layout).apply {
                        requestFocus()
                        setSelectedPosition(1)
                    }
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                screenshot("detail-episodes-empty")
                key(KeyEvent.KEYCODE_BACK)
            }
    }

    @Test fun playbackFeedbackIsNonFocusableAndExpires() {
        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            lateinit var loading: LoadingView
            lateinit var messages: MessageContainer
            lateinit var focus: View
            scenario.onActivity { activity ->
                focus =
                    activity.currentFocus
                        ?: activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).also {
                            it.isFocusable = true
                            it.requestFocus()
                        }
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                loading = LoadingView(activity)
                root.addView(loading, FrameLayout.LayoutParams(-1, -1))
                messages = MessageContainer(activity).apply { orientation = 1 }
                root.addView(
                    messages,
                    FrameLayout.LayoutParams(-2, -2).apply {
                        topMargin = 100
                        leftMargin = 40
                    }
                )
                loading.onPlayStateChanged(PlayState.STATE_PREPARING)
            }
            screenshot("player-preparing")
            scenario.onActivity {
                assertSame(focus, it.currentFocus)
                loading.onPlayStateChanged(PlayState.STATE_BUFFERING_PAUSED)
            }
            screenshot("player-buffering")
            scenario.onActivity {
                loading.onPlayStateChanged(PlayState.STATE_PLAYING)
                assertFalse(loading.findViewById<View>(PlayerR.id.loading_panel).isShown)
                messages.showMessage("当前字幕文件加载失败，请检查文件是否可用，然后在播放设置中重新选择。", MessageTime.LONG)
            }
            screenshot("player-message-long")
            scenario.onActivity {
                assertSame(focus, it.currentFocus)
                assertFullyVisible(messages.getChildAt(0))
            }
            val deadline = SystemClock.uptimeMillis() + 12000
            var count = 1
            while (count > 0 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(200)
                scenario.onActivity { count = messages.childCount }
            }
            assertEquals(0, count)
        }
    }

    @Test fun feedbackFitsBothRealPlayersAndBackReturnsFromControls() {
        val previous = PlayerConfig.getUsePlayerType()
        val file = File(context.getExternalFilesDir("ui-remaining-fixtures"), "playback.mp4")
        File("/sdcard/Download/ddplaytv-lease-playback-long.mp4").copyTo(file, overwrite = true)
        try {
            for (kernel in listOf(PlayerType.TYPE_EXO_PLAYER, PlayerType.TYPE_MPV_PLAYER)) {
                PlayerConfig.putUsePlayerType(kernel.value)
                withIndexedPlaybackFile(file) { descriptor ->
                    val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
                    val request =
                        runBlocking { provider.submit(PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.STORAGE_FILE)) }
                    assertTrue(request is PlaybackSubmissionResult.Accepted)
                    ActivityScenario
                        .launch<PlayerActivity>(
                            Intent(context, PlayerActivity::class.java)
                                .putExtra(PlaybackNavigation.REQUEST_ID, (request as PlaybackSubmissionResult.Accepted).requestId)
                        ).use { scenario ->
                            val deadline = SystemClock.uptimeMillis() + 20000
                            var playing = false
                            while (!playing && SystemClock.uptimeMillis() < deadline) {
                                scenario.onActivity {
                                    playing =
                                        (
                                            it
                                                .findViewById<FrameLayout>(
                                                    PlayerR.id.player_container
                                                ).getChildAt(0) as DanDanVideoPlayer
                                        ).isPlaying()
                                }
                                SystemClock.sleep(100)
                            }
                            assertTrue("Actual backend must play before visual feedback fixtures", playing)
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            settle()
                            lateinit var caller: View
                            scenario.onActivity {
                                caller = requireNotNull(it.currentFocus)
                                val loading = it.findViewById<View>(PlayerR.id.loading_panel).parent as LoadingView
                                loading.onPlayStateChanged(PlayState.STATE_BUFFERING_PAUSED)
                            }
                            screenshot("player-real-${kernel.value}-buffering")
                            scenario.onActivity {
                                assertSame(caller, it.currentFocus)
                                (
                                    it
                                        .findViewById<View>(
                                            PlayerR.id.loading_panel
                                        ).parent as LoadingView
                                ).onPlayStateChanged(PlayState.STATE_PLAYING)
                                it
                                    .findViewById<MessageContainer>(
                                        PlayerR.id.message_container
                                    ).showMessage("当前字幕文件加载失败，请检查文件是否可用，然后在播放设置中重新选择。", MessageTime.LONG)
                            }
                            screenshot("player-real-${kernel.value}-message")
                            scenario.onActivity {
                                assertFullyVisible(
                                    it.findViewById<MessageContainer>(PlayerR.id.message_container).getChildAt(0)
                                )
                            }
                            key(KeyEvent.KEYCODE_BACK)
                            key(KeyEvent.KEYCODE_BACK)
                        }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(previous)
            file.delete()
        }
    }

    private fun bangumi() =
        BangumiData(
            animeId = -1,
            animeTitle = "药屋少女的呢喃",
            userRating = 0,
            isOnAir = false,
            airDay = 1,
            isFavorited = false,
            isRestricted = false,
            rating = 9.1,
            typeDescription = "TV",
            summary = "在故事与日常之间，寻找每一处细微的线索。少女的旅程由此展开。".repeat(50),
            metadata = MutableList(8) { "制作信息 $it · 日本 · 2023" },
            tags = mutableListOf(TagData(1, "悬疑"), TagData(2, "日常")),
            episodes = MutableList(24) { EpisodeData(episodeId = "${it + 1}", episodeTitle = "第${it + 1}话 测试分集的长标题，用于核对换行与操作区域") },
            relateds = MutableList(3) { AnimeData(animeId = it, animeTitle = "相关作品 ${it + 1}") },
            similars = MutableList(12) { AnimeData(animeId = it + 10, animeTitle = "推荐作品 ${it + 1}") }
        )

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(800)
    }

    private fun screenshot(name: String) {
        settle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir("ui-remaining-verification"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertFullyVisible(view: View) {
        val bounds = Rect()
        assertTrue(view.getGlobalVisibleRect(bounds))
        assertTrue(
            "Clipped: $bounds / ${view.width}x${view.height}",
            bounds.width() >= view.width - 2 && bounds.height() >= view.height - 2
        )
    }

    private fun assertMeasuredBounds(view: View) {
        assertEquals("Selected item width must not lose its spacing", view.measuredWidth, view.width)
        assertEquals("Selected item height must not lose its spacing", view.measuredHeight, view.height)
    }
}
