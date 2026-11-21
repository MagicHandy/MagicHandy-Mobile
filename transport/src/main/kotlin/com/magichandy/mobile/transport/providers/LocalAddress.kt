package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.transport.http.HttpsOnly
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Constrain the socket's actual destination as well as DNS answers. Every credential-free LAN
 * client is origin-bound.
 */
internal class PrivateAddressRequired :
    UnknownHostException("This connection requires a private address")

internal fun privateAddress(address: InetAddress): Boolean {
    val bytes = address.address
    if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return true
    return address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        bytes.size == 4 && address.isSiteLocalAddress
}

internal fun serverAddress(text: String): HttpUrl? {
    val input = text.trim()
    if (input.isEmpty() || input.length > 2048 || input.any(Char::isWhitespace)) return null
    val url = (if ("://" in input) input else "http://" + input).toHttpUrlOrNull() ?: return null
    if (
        url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            url.fragment != null ||
            url.query != null ||
            url.encodedPath !in setOf("/", "/v1", "/v1/")
    )
        return null
    return url.newBuilder().encodedPath("/").build()
}

internal fun originClient(client: OkHttpClient, origin: HttpUrl): OkHttpClient {
    val dns = client.dns
    fun check(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !privateAddress(it) })
            throw PrivateAddressRequired()
        return addresses
    }
    return client
        .newBuilder()
        .apply { interceptors().remove(HttpsOnly) }
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != origin.host) throw UnknownHostException("Origin changed")
                    return check(dns.lookup(hostname))
                }
            }
        )
        .addInterceptor { chain ->
            val url = chain.request().url
            if (url.host != origin.host || url.port != origin.port || url.scheme != origin.scheme)
                throw IOException("Origin changed")
            // OkHttp can bypass Dns for numeric literals. Reject those before connect as well.
            if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' })
                check(listOf(InetAddress.getByName(url.host)))
            chain.proceed(chain.request())
        }
        .addNetworkInterceptor { chain ->
            check(listOf(requireNotNull(chain.connection()?.route()?.socketAddress?.address)))
            chain.proceed(chain.request())
        }
        .build()
}
