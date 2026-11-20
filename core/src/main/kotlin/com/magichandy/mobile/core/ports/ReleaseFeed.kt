package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.UpdateCheck

/** Fetch public APK release metadata only in response to a user-initiated check. */
public fun interface ReleaseFeed {
    public suspend fun check(installed: String): UpdateCheck
}
