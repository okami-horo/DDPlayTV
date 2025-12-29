package com.xyoye.dandanplay.ui.setting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.PolicySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 开发者设置中的日志偏好项测试：验证级别选择与调试开关同步更新 LogRuntimeState。
 */
@RunWith(AndroidJUnit4::class)
class DeveloperLoggingPreferenceTest {
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
        val runtime = LogSystem.getRuntimeState()
        LogSystem.updateLoggingPolicy(
            runtime.activePolicy.copy(defaultLevel = LogLevel.WARN),
            PolicySource.USER_OVERRIDE,
        )

        val levelUpdated = LogSystem.getRuntimeState()
        assertEquals(LogLevel.WARN, levelUpdated.activePolicy.defaultLevel)
        assertEquals(PolicySource.USER_OVERRIDE, levelUpdated.policySource)

        LogSystem.startDebugSession()
        val debugEnabled = LogSystem.getRuntimeState()
        assertTrue(debugEnabled.activePolicy.enableDebugFile)
        assertTrue(debugEnabled.debugSessionEnabled)
        assertEquals(DebugToggleState.ON_CURRENT_SESSION, debugEnabled.debugToggleState)

        LogSystem.stopDebugSession()
        val debugDisabled = LogSystem.getRuntimeState()
        assertFalse(debugDisabled.activePolicy.enableDebugFile)
        assertFalse(debugDisabled.debugSessionEnabled)
        assertEquals(DebugToggleState.OFF, debugDisabled.debugToggleState)
    }

    private fun resetLoggingState() {
        LogSystem.updateLoggingPolicy(LogPolicy.defaultReleasePolicy(), PolicySource.DEFAULT)
        LogSystem.stopDebugSession()
    }
}
