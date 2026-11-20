package com.magichandy.mobile

import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.platform.platformCore

/** Builds the single process-wide Android core. */
internal fun createCore(app: MagicHandyApp): MagicHandyCore = platformCore(app)

internal const val simulatedDevices = false
