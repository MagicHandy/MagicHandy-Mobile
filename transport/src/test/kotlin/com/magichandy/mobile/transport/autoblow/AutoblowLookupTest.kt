package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.transport.providers.reply
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient

internal class AutoblowLookupTest {
    @Test
    fun `discovery requires the exact device family and an allowlisted regional host`() = runTest {
        var response = ""
        val requests = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    requests += req.url.host
                    req.reply(response)
                }
                .build()
        val wire = AutoblowCloud(DeviceFamily.VacuGlide, Secret("test-token"), http, http)
        for (invalid in
            listOf(
                """{"connected":true,"cluster":"test.autoblowapi.com"}""",
                """{"connected":true,"deviceType":"autoblow-ultra","cluster":"test.autoblowapi.com"}""",
                """{"connected":true,"deviceType":"vacuglide","cluster":"another.invalid"}""",
            )) {
            response = invalid
            assertFailsWith<IOException> { wire.discover() }
        }
        assertEquals(setOf("latency.autoblowapi.com"), requests.toSet())
        response =
            """{"connected":true,"deviceType":"vacuglide","cluster":"test.autoblowapi.com"}"""
        wire.discover()
        response = """{"operationalMode":"ONLINE_CONNECTED"}"""
        wire.state(priorityRead = true)
        assertEquals("test.autoblowapi.com", requests.last())
    }
}
