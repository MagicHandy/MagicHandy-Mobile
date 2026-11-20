package com.magichandy.mobile

import android.app.Application
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.platform.AndroidHost
import com.magichandy.mobile.platform.CoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide owner of the core (system design §1). The activity can be destroyed and
 * recreated at any time; the core lives here and outlives it.
 */
class MagicHandyApp : Application(), CoreOwner {
    /** Android's side of a run: the foreground service, its notification and system access. */
    override val host by lazy { AndroidHost(this) }

    /** Re-reads the Bluetooth radio. The release platform graph sets it; debug has no radio. */
    internal var refreshRadio: () -> Unit = {}

    /** Owns the core's work and executors for the life of the process. */
    internal val processScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val instance = lazy { createCore(this) }

    /** The one core. Every Stop surface and screen talks to this instance. */
    override val core: MagicHandyCore
        get() = instance.value

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Before the core exists there is nothing to release.
        if (instance.isInitialized()) core.trimMemory()
    }
}
