package com.xyoye.player.kernel.impl.mpv

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MpvNativeEventDispatchTest {
    @Test
    fun fileUrisBecomeDecodedPathsWhileNetworkUrlsRemainUnchanged() {
        assertEquals("/sdcard/a b#c%.mp4", MpvNativeBridge.nativePath("file:/sdcard/a%20b%23c%25.mp4"))
        assertEquals("/sdcard/a b.mp4", MpvNativeBridge.nativePath("file:///sdcard/a%20b.mp4"))
        assertEquals("/sdcard/a.mp4", MpvNativeBridge.nativePath("file://localhost/sdcard/a.mp4"))
        listOf("/sdcard/a%20b.mp4", "https://example.com/a%20b.mp4?token=a%2Cb", "content://media/video/42").forEach {
            assertEquals(it, MpvNativeBridge.nativePath(it))
        }
    }

    @Test
    fun clearingListenerDiscardsAlreadyQueuedNativeEvent() {
        val bridge = MpvNativeBridge()
        val events = mutableListOf<MpvNativeBridge.Event>()
        bridge.setEventListener { events.add(it) }

        postPrepared(bridge)
        bridge.clearEventListener()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(events.isEmpty())
    }

    @Test
    fun replacingListenerDiscardsOldEventAndDeliversNewEvent() {
        val bridge = MpvNativeBridge()
        val oldEvents = mutableListOf<MpvNativeBridge.Event>()
        val newEvents = mutableListOf<MpvNativeBridge.Event>()
        bridge.setEventListener { oldEvents.add(it) }
        postPrepared(bridge)

        bridge.setEventListener { newEvents.add(it) }
        postPrepared(bridge)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(oldEvents.isEmpty())
        assertEquals(listOf(MpvNativeBridge.Event.Prepared), newEvents)
        bridge.destroy()
    }

    @Test
    fun destroyingBridgeDiscardsAlreadyQueuedNativeEvent() {
        val bridge = MpvNativeBridge()
        val events = mutableListOf<MpvNativeBridge.Event>()
        bridge.setEventListener { events.add(it) }

        postPrepared(bridge)
        bridge.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(events.isEmpty())
    }

    private fun postPrepared(bridge: MpvNativeBridge) {
        bridge.dispatchEvent(MpvNativeBridge.Event.Prepared)
    }

    @Test
    fun replacingSourceDiscardsAlreadyQueuedPreparedEvent() {
        val bridge = MpvNativeBridge()
        val events = mutableListOf<MpvNativeBridge.Event>()
        bridge.setEventListener { events.add(it) }
        postPrepared(bridge)
        bridge.setDataSource("replacement.mkv", emptyMap()) { }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events.isEmpty())
        bridge.destroy()
    }
}
