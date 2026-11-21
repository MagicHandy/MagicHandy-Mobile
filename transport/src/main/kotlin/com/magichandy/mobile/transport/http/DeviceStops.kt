package com.magichandy.mobile.transport.http

import okhttp3.Dispatcher
import okhttp3.OkHttpClient

// The motion engine bounds concurrent Stops per owner. A slow device must not queue another
// device's Stop.
private val stopDispatcher =
    Dispatcher().apply {
        maxRequests = Int.MAX_VALUE
        maxRequestsPerHost = Int.MAX_VALUE
    }

internal fun OkHttpClient.forDeviceStops(): OkHttpClient =
    newBuilder().dispatcher(stopDispatcher).build()
