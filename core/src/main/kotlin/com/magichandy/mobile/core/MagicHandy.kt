package com.magichandy.mobile.core

import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.session.MobileSession

/**
 * Create the process-owned backend. Supply Android ports in release and simulated ports in debug.
 */
public fun createMagicHandyCore(dependencies: CoreDependencies): MagicHandyCore =
    MobileSession(dependencies)
