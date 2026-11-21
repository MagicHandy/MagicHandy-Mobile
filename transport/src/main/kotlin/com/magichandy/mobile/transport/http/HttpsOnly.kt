package com.magichandy.mobile.transport.http

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Release clients require TLS. A named LAN client replaces this with its own private-origin guard.
 */
public object HttpsOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.isHttps) throw IOException("Hosted connection requires HTTPS")
        return chain.proceed(chain.request())
    }
}
