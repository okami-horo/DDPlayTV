package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.SamplingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogPolicyTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultReleasePolicyDisablesFileWrites() {
        val policy = LogPolicy.defaultReleasePolicy()
        assertEquals(LogLevel.INFO, policy.defaultLevel)
        assertFalse(policy.enableDebugFile)
        assertFalse(policy.exportable)
    }

    @Test
    fun debugSessionPolicyEnablesFileWrites() {
        val policy = LogPolicy.debugSessionPolicy(minLevel = LogLevel.DEBUG, enableFile = true)
        assertEquals(LogLevel.DEBUG, policy.defaultLevel)
        assertEquals(true, policy.enableDebugFile)
        assertEquals(true, policy.exportable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun samplingRuleRequiresValidRange() {
        SamplingRule(LogModule.CORE, LogLevel.INFO, sampleRate = 1.5)
    }

    @Test
    fun disableDebugFilePreventsDiskWrites() {
        val context = TestLogContext(tempFolder.newFolder("policy_files"))
        val writer = LogWriter(context)
        val runtimeState = LogRuntimeState(
            activePolicy = LogPolicy.defaultReleasePolicy(),
            debugToggleState = DebugToggleState.ON_CURRENT_SESSION,
            debugSessionEnabled = true
        )
        writer.updateRuntimeState(runtimeState)
        writer.submit(
            LogEvent(
                level = LogLevel.INFO,
                module = LogModule.CORE,
                message = "should stay in logcat only"
            )
        )
        Thread.sleep(200)
        assertFalse(LogPaths.currentLogFile(context).exists())
    }
}
