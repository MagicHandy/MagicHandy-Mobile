package com.magichandy.mobile

import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.transport.simulated.simulatedDependencies

/**
 * Creates the debug core: the real core with in-memory stores, five simulated devices and a canned
 * model (docs/backend/wiring.md). Simulation exists only in debug builds, so release builds can
 * never reach it.
 */
internal fun createCore(app: MagicHandyApp): MagicHandyCore =
    createMagicHandyCore(simulatedDependencies(app.processScope))
