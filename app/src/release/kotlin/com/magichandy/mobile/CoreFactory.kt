package com.magichandy.mobile

import com.magichandy.mobile.core.api.MagicHandyCore

/**
 * Creates the release core.
 *
 * TODO(M1): build `CoreDependencies` from the Android platform layer (Room, Keystore, GATT, the
 *   foreground service and the sign-in redirect) and pass it to `createMagicHandyCore`; see
 *   docs/backend/wiring.md.
 */
internal fun createCore(app: MagicHandyApp): MagicHandyCore =
    throw UnsupportedOperationException("${app.packageName} has no release core before M1.")
