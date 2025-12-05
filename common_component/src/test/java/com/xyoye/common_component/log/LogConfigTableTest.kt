package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.PolicySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogConfigTableTest {

    private val defaultPolicy = LogPolicy.defaultReleasePolicy()

    @Test
    fun mmkvPolicyRoundTrip() {
        val storage = InMemoryLogConfigStorage()
        val repository = LogPolicyRepository(defaultPolicy, { 100L }, storage)
        val loaded = repository.loadFromStorage()
        assertEquals(LogLevel.INFO, loaded.activePolicy.defaultLevel)
        assertFalse(loaded.debugSessionEnabled)

        val customPolicy = LogPolicy(
            name = "debug-session-ui",
            defaultLevel = LogLevel.DEBUG,
            enableDebugFile = true,
            samplingRules = emptyList(),
            exportable = true
        )
        repository.updatePolicy(customPolicy, PolicySource.USER_OVERRIDE)
        val runtime = repository.updateDebugState(DebugToggleState.ON_CURRENT_SESSION)

        assertTrue(runtime.activePolicy.enableDebugFile)
        assertTrue(runtime.debugSessionEnabled)
        assertEquals(PolicySource.USER_OVERRIDE, runtime.policySource)
        assertEquals(DebugToggleState.ON_CURRENT_SESSION, runtime.debugToggleState)
        assertEquals(LogLevel.DEBUG, runtime.activePolicy.defaultLevel)

        assertEquals(customPolicy.name, storage.policyName)
        assertEquals(customPolicy.defaultLevel.name, storage.defaultLevel)
        assertTrue(storage.debugFileEnabled)
        assertEquals(DebugToggleState.ON_CURRENT_SESSION.name, storage.debugToggleState)

        val reloaded = LogPolicyRepository(defaultPolicy, { 200L }, storage).loadFromStorage()
        assertEquals(customPolicy.name, reloaded.activePolicy.name)
        assertEquals(customPolicy.defaultLevel, reloaded.activePolicy.defaultLevel)
        assertEquals(customPolicy.enableDebugFile, reloaded.activePolicy.enableDebugFile)
        assertTrue(reloaded.debugSessionEnabled)
        assertEquals(DebugToggleState.ON_CURRENT_SESSION, reloaded.debugToggleState)
    }
}

private class InMemoryLogConfigStorage : LogConfigStorage {
    var policyName: String? = null
    var defaultLevel: String? = null
    var debugFileEnabled: Boolean = false
    var exportable: Boolean = false
    var policySource: String? = null
    var debugToggleState: String? = null
    var lastUpdated: Long = 0L

    override fun readPolicyName(): String? = policyName

    override fun readDefaultLevel(): String? = defaultLevel

    override fun readDebugFileEnabled(): Boolean = debugFileEnabled

    override fun readExportable(): Boolean = exportable

    override fun readPolicySource(): String? = policySource

    override fun readDebugToggleState(): String? = debugToggleState

    override fun readLastPolicyUpdateTime(): Long = lastUpdated

    override fun write(state: LogRuntimeState) {
        policyName = state.activePolicy.name
        defaultLevel = state.activePolicy.defaultLevel.name
        debugFileEnabled = state.activePolicy.enableDebugFile
        exportable = state.activePolicy.exportable
        policySource = state.policySource.name
        debugToggleState = state.debugToggleState.name
        lastUpdated = state.lastPolicyUpdateTime
    }
}
