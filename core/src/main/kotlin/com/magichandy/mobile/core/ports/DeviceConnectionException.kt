package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.DeviceConnectionFailure
import java.io.IOException

/** A failed device handshake or reachability check with a redacted cause. */
public class DeviceConnectionException(public val failure: DeviceConnectionFailure) :
    IOException("Device connection failed")
