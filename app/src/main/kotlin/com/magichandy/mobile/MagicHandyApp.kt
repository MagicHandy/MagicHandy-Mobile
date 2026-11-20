package com.magichandy.mobile

import android.app.Application
import com.magichandy.mobile.core.api.MagicHandyCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide owner of the core (system design §1). The activity can be destroyed and
 * recreated at any time; the core lives here and outlives it.
 */
class MagicHandyApp : Application() {
    /** Owns the core's work and executors for the life of the process. */
    internal val processScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The one core. Every Stop surface and screen talks to this instance. */
    val core: MagicHandyCore by lazy { createCore(this) }
}
