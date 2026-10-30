package com.xyoye.user_component.ui.fragment.personal

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.xyoye.data_component.data.LoginData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PersonalFragmentViewModelTest {
    @Test
    fun loginReplayDoesNotRepeatRequestAndLogoutRejectsLateUserCounts() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var calls = 0
            val gate = CompletableDeferred<Pair<Int, Int>>()
            val vm =
                PersonalFragmentViewModel {
                    calls++
                    withContext(NonCancellable) { gate.await() }
                }
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                val user = LoginData("user", "token", "User", null)
                vm.onLoginChanged(user)
                runCurrent()
                vm.onLoginChanged(user)
                vm.onLoginChanged(user.copy(token = "refreshed"))
                runCurrent()
                assertEquals(1, calls)
                vm.onLoginChanged(null)
                gate.complete(12 to 34)
                runCurrent()
                assertEquals(0 to 0, vm.relationLiveData.value)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
