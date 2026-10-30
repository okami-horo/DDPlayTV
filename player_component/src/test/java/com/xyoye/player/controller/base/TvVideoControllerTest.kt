package com.xyoye.player.controller.base

import android.os.Looper
import androidx.activity.ComponentActivity
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.player.wrapper.InterDanmuController
import com.xyoye.player.wrapper.InterSettingController
import com.xyoye.player.wrapper.InterSubtitleController
import com.xyoye.player.wrapper.InterVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerUiTestSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TvVideoControllerTest {
    @Test fun sameSourceAccumulatesAndCommitsAtEightHundredMilliseconds() =
        withController { controller, player ->
            controller.seekBy(10_000)
            controller.seekBy(10_000)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(799))
            assertTrue(player.seeks.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(listOf(25_000L), player.seeks)
        }

    @Test fun changedSourceDropsDelayedSeekEvenWithoutExplicitCleanup() =
        withController { controller, player ->
            controller.seekBy(10_000)
            player.source = PlayerUiTestSource("next")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertTrue(player.seeks.isEmpty())
        }

    @Test fun sameSourceBackendReplacementAndReleaseCancelPendingSeek() =
        withController { controller, player ->
            controller.seekBy(10_000)
            controller.cancelPendingSeek()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertTrue(player.seeks.isEmpty())
            controller.seekBy(10_000)
            controller.release()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertTrue(player.seeks.isEmpty())
            controller.seekBy(10_000)
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertTrue(player.seeks.isEmpty())
        }

    private fun withController(block: (TestController, Player) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val player = Player()
            val controller = TestController(activity.get())
            controller.setMediaPlayer(player.proxy)
            activity.get().setContentView(controller)
            block(controller, player)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private class TestController(
        context: ComponentActivity
    ) : TvVideoController(context) {
        fun seekBy(offset: Long) = changePosition(offset)

        override fun getDanmuController() = stub<InterDanmuController>()

        override fun getSubtitleController() = stub<InterSubtitleController>()

        override fun getSettingController() = stub<InterSettingController>()

        override fun setTrackAdded(track: com.xyoye.data_component.bean.VideoTrackBean) = Unit

        override fun showMessage(
            text: String,
            time: com.xyoye.player.utils.MessageTime
        ) = Unit
    }

    private class Player {
        var source: BaseVideoSource = PlayerUiTestSource("current")
        val seeks = mutableListOf<Long>()
        val proxy =
            stub<InterVideoPlayer> { name, args ->
                when (name) {
                    "getVideoSource" -> source
                    "getDuration" -> 60_000L
                    "getCurrentPosition" -> 5_000L
                    "isSeekable" -> true
                    "seekTo" -> {
                        seeks += args!![0] as Long
                        null
                    }
                    else -> null
                }
            }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> stub(noinline answer: (String, Array<out Any?>?) -> Any? = { _, _ -> null }): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                answer(method.name, args) ?: when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            } as T
    }
}
