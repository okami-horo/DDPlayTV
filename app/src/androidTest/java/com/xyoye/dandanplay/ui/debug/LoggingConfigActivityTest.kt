package com.xyoye.dandanplay.ui.debug

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.PolicySource
import com.xyoye.dandanplay.R
import org.hamcrest.Matchers.anything
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LoggingConfigActivity UI 测试：验证级别选择与调试开关会同步更新 LogRuntimeState。
 */
@RunWith(AndroidJUnit4::class)
class LoggingConfigActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!LogSystem.isInitialized()) {
            LogSystem.init(context)
            LogSystem.loadPolicyFromStorage()
        }
        resetLoggingState()
    }

    @After
    fun tearDown() {
        resetLoggingState()
    }

    @Test
    fun updateLevelAndToggleDebugLogging() {
        ActivityScenario.launch(LoggingConfigActivity::class.java).use {
            selectLogLevel(position = 2) // WARN

            val levelUpdated = LogSystem.getRuntimeState()
            assertEquals(LogLevel.WARN, levelUpdated.activePolicy.defaultLevel)
            assertEquals(PolicySource.USER_OVERRIDE, levelUpdated.policySource)

            toggleDebugLogging()
            val debugEnabled = LogSystem.getRuntimeState()
            assertTrue(debugEnabled.activePolicy.enableDebugFile)
            assertTrue(debugEnabled.debugSessionEnabled)
            assertEquals(DebugToggleState.ON_CURRENT_SESSION, debugEnabled.debugToggleState)

            toggleDebugLogging()
            val debugDisabled = LogSystem.getRuntimeState()
            assertFalse(debugDisabled.activePolicy.enableDebugFile)
            assertFalse(debugDisabled.debugSessionEnabled)
            assertEquals(DebugToggleState.OFF, debugDisabled.debugToggleState)
        }
    }

    private fun selectLogLevel(position: Int) {
        onView(withId(R.id.logging_level_spinner)).perform(click())
        onData(anything()).atPosition(position).perform(click())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun toggleDebugLogging() {
        onView(withId(R.id.debug_log_switch)).perform(click())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun resetLoggingState() {
        LogSystem.updateLoggingPolicy(LogPolicy.defaultReleasePolicy(), PolicySource.DEFAULT)
        LogSystem.stopDebugSession()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
