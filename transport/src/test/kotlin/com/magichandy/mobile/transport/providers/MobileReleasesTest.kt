package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.transport.http.MobileReleases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

internal class MobileReleasesTest {
    @Test
    fun `manual check compares numerical version fields and consumes only public metadata`() =
        runTest {
            var status = 200
            var body = """{"tag_name":"v0.10.3"}"""
            val feed =
                MobileReleases(
                    MemoryHttp { req ->
                        assertEquals(
                            "/repos/MagicHandy/MagicHandy-Mobile/releases/latest",
                            req.url.encodedPath,
                        )
                        assertNull(req.header("Authorization"))
                        req.reply(body).newBuilder().code(status).build()
                    }
                )
            assertEquals(UpdateCheck.Available("v0.10.3"), feed.check("0.9.9"))
            assertEquals(UpdateCheck.UpToDate, feed.check("0.10.3"))
            assertEquals(UpdateCheck.Available("v0.10.3"), feed.check("0.9.9-device-test"))
            assertEquals(UpdateCheck.UpToDate, feed.check("0.10.3-device-test"))
            body = "[]"
            assertEquals(UpdateCheck.Failed, feed.check("0.1.0"))
            for (denied in listOf(401, 403, 404, 429, 503)) {
                status = denied
                assertEquals(UpdateCheck.Failed, feed.check("0.1.0"))
            }
        }
}
