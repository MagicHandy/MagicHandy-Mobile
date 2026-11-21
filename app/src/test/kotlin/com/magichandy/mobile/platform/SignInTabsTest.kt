package com.magichandy.mobile.platform

import android.app.Application
import androidx.activity.ComponentActivity
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
internal class SignInTabsTest {
    @Test
    fun callbackWaitsForTheCurrentActivityToResumeAfterRecreation() = runTest {
        val tabs = SignInTabs()
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        tabs.attach(first.get())
        first.pause()
        val waiting = launch { tabs.awaitForeground() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        tabs.detach(first.get())
        first.stop().destroy()
        val replacement = Robolectric.buildActivity(ComponentActivity::class.java).create().start()
        tabs.attach(replacement.get())
        runCurrent()
        assertFalse(waiting.isCompleted)
        replacement.resume()
        runCurrent()
        assertTrue(waiting.isCompleted)
        tabs.detach(replacement.get())
        replacement.pause().stop().destroy()
    }
}
