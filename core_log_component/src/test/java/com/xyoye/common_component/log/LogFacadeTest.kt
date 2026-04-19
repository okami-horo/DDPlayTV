package com.xyoye.common_component.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.http.HttpLogServerManager
import com.xyoye.common_component.log.http.HttpLogServerConfig
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class LogFacadeTest {
    private val received = CopyOnWriteArrayList<LogEvent>()

    @Before
    fun setUp() {
        LogSystem.resetForTests()
        HttpLogServerManager.config = InMemoryHttpLogConfig()
        LogSystem.policyRepositoryFactory = { defaultPolicy ->
            LogPolicyRepository(defaultPolicy, storage = NoopLogConfigStorage())
        }
        LogSystem.writerFactory = { httpSink ->
            LogWriter(
                httpLogSink = { event ->
                    received.add(event)
                    httpSink(event)
                },
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy =
            LogPolicy(
                name = "test",
                defaultLevel = LogLevel.DEBUG,
                samplingRules = emptyList(),
            )
        LogSystem.init(context = context, defaultPolicy = policy)
    }

    @After
    fun tearDown() {
        LogSystem.resetForTests()
        received.clear()
    }

    @Test
    fun blankMessageIsReplacedWithPlaceholder() {
        LogFacade.d(
            module = LogModule.PLAYER,
            tag = "LogFacadeTest",
            message = "  \n\t",
        )

        Thread.sleep(200)

        assertTrue(received.isNotEmpty())
        val event = received.last()
        assertEquals(LogLevel.DEBUG, event.level)
        assertEquals(LogModule.PLAYER, event.module)
        assertEquals("<empty>", event.message)
    }
}

private class NoopLogConfigStorage : LogConfigStorage {
    override fun readPolicyName(): String? = null

    override fun readDefaultLevel(): String? = null

    override fun readPolicySource(): String? = null

    override fun readDebugToggleState(): String? = null

    override fun readLastPolicyUpdateTime(): Long = 0L

    override fun write(state: LogRuntimeState) {
        // no-op
    }
}

private class InMemoryHttpLogConfig : HttpLogServerConfig {
    private var enabled: Boolean = false
    private var port: Int = 17010
    private var token: String = "test-token-00000000"
    private var retentionDays: Int = 7

    override fun isHttpLogServerEnabled(): Boolean = enabled

    override fun putHttpLogServerEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun getHttpLogServerPort(): Int = port

    override fun putHttpLogServerPort(port: Int) {
        this.port = port
    }

    override fun getHttpLogServerToken(): String? = token

    override fun putHttpLogServerToken(token: String) {
        this.token = token
    }

    override fun getHttpLogRetentionDays(): Int = retentionDays

    override fun putHttpLogRetentionDays(days: Int) {
        retentionDays = days
    }
}
