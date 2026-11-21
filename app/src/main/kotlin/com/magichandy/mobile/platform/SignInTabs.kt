package com.magichandy.mobile.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.magichandy.mobile.core.api.Secret
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Main-thread owner of the Custom Tab. Its result key follows the attempt across Activity
 * recreation.
 */
internal class SignInTabs {
    private class Attempt(val dismissed: () -> Unit) {
        val key = "chatgpt-" + UUID.randomUUID()
    }

    private var activity: ComponentActivity? = null
    private var current: Attempt? = null
    private var launcher: ActivityResultLauncher<Intent>? = null
    private val resumed = MutableStateFlow(false)
    private val visibility =
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                resumed.value = true
            }

            override fun onPause(owner: LifecycleOwner) {
                resumed.value = false
            }
        }

    /** Android can restrict background data while the Custom Tab owns the foreground. */
    suspend fun awaitForeground() {
        resumed.first { it }
    }

    fun attach(owner: ComponentActivity) {
        activity?.let(::detach)
        activity = owner
        owner.lifecycle.addObserver(visibility)
        current?.let { register(owner, it) }
    }

    fun detach(owner: ComponentActivity) {
        if (activity !== owner) return
        owner.lifecycle.removeObserver(visibility)
        resumed.value = false
        launcher?.unregister()
        launcher = null
        activity = null
    }

    fun open(url: Secret, onDismissed: () -> Unit) {
        val owner =
            activity?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                ?: throw ActivityNotFoundException("No visible sign-in activity")
        launcher?.unregister()
        val attempt = Attempt(onDismissed)
        current = attempt
        register(owner, attempt)
        try {
            launcher?.launch(
                CustomTabsIntent.Builder().build().intent.setData(url.reveal().toUri())
            )
        } catch (e: ActivityNotFoundException) {
            complete(attempt)
            throw e
        }
    }

    private fun register(owner: ComponentActivity, attempt: Attempt) {
        val registered =
            owner.activityResultRegistry.register(
                attempt.key,
                ActivityResultContracts.StartActivityForResult(),
            ) {
                complete(attempt)
            }
        if (current === attempt) launcher = registered else registered.unregister()
    }

    private fun complete(attempt: Attempt) {
        if (current !== attempt) return
        current = null
        launcher?.unregister()
        launcher = null
        attempt.dismissed()
    }
}
