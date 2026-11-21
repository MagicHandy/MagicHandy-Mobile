package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceConnectionFailure.Reason
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.DeviceConnectionException
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal class HandyCloudConnectionTest {
    private class Device(var mode: Int) {
        val calls = mutableListOf<String>()
        var modeAckOnly = false
        var connected = true
        var applyMode = true
        var hspStop = """{"result":{"play_state":0}}"""
        var hspState = """{"result":{"play_state":0}}"""
        var overrideResponse: ((String) -> Pair<Int, String>?)? = null
        val wire =
            CloudWire(
                Secret("fixture-key"),
                Secret("fixture-app"),
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val route = request.method + " " + request.url.encodedPath
                        calls += route
                        val response =
                            overrideResponse?.invoke(route)
                                ?: (200 to
                                    when (route) {
                                        "GET /connected" ->
                                            """{"result":{"connected":$connected}}"""
                                        "GET /mode" ->
                                            """{"result":{"mode":$mode,"mode_session_id":12}}"""
                                        "PUT /mode2" -> {
                                            val payload =
                                                Buffer()
                                                    .also { request.body?.writeTo(it) }
                                                    .readUtf8()
                                            if (payload == """{"mode":7}""" && applyMode) mode = 7
                                            if (modeAckOnly) """{"result":"ok"}"""
                                            else """{"result":{"mode":$mode}}"""
                                        }
                                        "PUT /hsp/stop" -> hspStop
                                        "GET /hsp/state" -> hspState
                                        else -> """{"error":{"code":100004,"connected":true}}"""
                                    })
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(response.first)
                            .message("fixture")
                            .body(response.second.toResponseBody())
                            .build()
                    }
                    .build(),
                "https://fixture.invalid/".toHttpUrl(),
            )
        val owner =
            HandyTransport(
                DeviceRef(
                    DeviceId("fixture"),
                    "Fixture",
                    DeviceFamily.Handy,
                    ConnectionKind.HandyCloud,
                ),
                wire,
            )
    }

    @Test
    fun `connecting a cloud-online idle Handy succeeds before any HSP session exists`() = runTest {
        val device = Device(7)
        assertEquals(StopEvidence.Acknowledged, device.owner.confirmIdleForConnection())
        assertEquals(listOf("GET /connected", "GET /mode"), device.calls)
        device.calls.clear()
        assertTrue(requireNotNull(device.owner.checkConnection).invoke())
        assertEquals(listOf("GET /connected"), device.calls)
    }

    @Test
    fun `button mode is switched only to idle and an acknowledgement alone needs readback`() =
        runTest {
            val device = Device(6)
            device.modeAckOnly = true
            assertEquals(StopEvidence.Acknowledged, device.owner.confirmIdleForConnection())
            assertEquals(
                listOf("GET /connected", "GET /mode", "PUT /mode2", "GET /mode"),
                device.calls,
            )
        }

    @Test
    fun `offline errors are redacted and never reach a mode-changing request`() = runTest {
        for ((status, body, reason) in
            listOf(
                Triple(200, """{"result":{"connected":false}}""", Reason.Offline),
                Triple(200, """{"error":{"code":1001,"connected":false}}""", Reason.Offline),
                Triple(200, """{"error":{"code":1002,"connected":true}}""", Reason.TimedOut),
                Triple(401, "private response", Reason.AccessDenied),
                Triple(429, "private response", Reason.RateLimited),
                Triple(503, "<html>unavailable</html>", Reason.ServiceUnavailable),
                Triple(200, """{"result":{"connected":"true"}}""", Reason.InvalidReply),
            )) {
            val device = Device(6)
            device.overrideResponse = { status to body }
            val exception =
                assertFailsWith<DeviceConnectionException> {
                    device.owner.confirmIdleForConnection()
                }
            assertEquals(reason, exception.failure.reason)
            assertEquals(listOf("GET /connected"), device.calls)
        }
    }

    @Test
    fun `maintenance unknown mode and unverified mode change cannot make an owner ready`() =
        runTest {
            for (mode in listOf(3, 5, 18)) {
                val device = Device(mode)
                assertFailsWith<DeviceConnectionException> {
                    device.owner.confirmIdleForConnection()
                }
                assertEquals(listOf("GET /connected", "GET /mode"), device.calls)
            }
            val device = Device(6)
            device.modeAckOnly = true
            device.applyMode = false
            val failure =
                assertFailsWith<DeviceConnectionException> {
                    device.owner.confirmIdleForConnection()
                }
            assertEquals(Reason.IdleUnconfirmed, failure.failure.reason)
        }

    @Test
    fun `Stop outside an initialized HSP session still requires affirmative device idle`() =
        runTest {
            for (wrongMode in listOf(false, true)) {
                val device = Device(7)
                if (wrongMode) device.hspStop = """{"error":{"code":100004,"connected":true}}"""
                assertEquals(StopEvidence.Acknowledged, device.wire.stop { true })
                assertEquals("PUT /hsp/stop", device.calls.first())
                assertEquals("GET /mode", device.calls.last())
            }
            val active = Device(4)
            active.applyMode = false
            assertIs<StopEvidence.Unconfirmed>(active.wire.stop { true })
        }
}
