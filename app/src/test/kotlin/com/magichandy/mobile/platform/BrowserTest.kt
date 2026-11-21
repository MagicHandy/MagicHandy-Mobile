package com.magichandy.mobile.platform

import com.magichandy.mobile.core.api.Secret
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowserTest {
    @Test
    fun `only the bound loopback host and one matching state complete sign in`() =
        runBlocking<Unit> {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val launched = CompletableDeferred<Unit>()
                val returning = CompletableDeferred<Unit>()
                val foreground = CompletableDeferred<Unit>()
                val dismissed = CompletableDeferred<() -> Unit>()
                val browser =
                    LoopbackBrowser(
                        scope,
                        {
                            returning.complete(Unit)
                            foreground.await()
                        },
                        { "Vuelve a la aplicación." },
                    ) { _, close ->
                        dismissed.complete(close)
                        launched.complete(Unit)
                    }
                val uri = URI(browser.redirectUri)
                assertEquals("127.0.0.1", uri.host)
                val auth = Secret("https://synthetic.invalid/authorize?state=nonce")
                val result = async(Dispatchers.IO) { browser.openAndAwait(auth) }
                withTimeout(3000) { launched.await() }
                fun send(host: String, state: String): String =
                    Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), uri.port).use {
                        socket ->
                        socket.soTimeout = 3000
                        val request =
                            "GET /auth/callback?code=synthetic&state=" +
                                state +
                                " HTTP/1.1\r\nHost: " +
                                host +
                                "\r\n\r\n"
                        socket.getOutputStream().write(request.encodeToByteArray())
                        socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                    }
                assertTrue(send("wrong.invalid", "nonce").contains("400"))
                assertTrue(send(uri.authority, "different").contains("400"))
                val page = send(uri.authority, "nonce")
                assertTrue(page.startsWith("HTTP/1.1 200"))
                assertTrue(page.endsWith("Vuelve a la aplicación."))
                assertTrue(
                    page.contains(
                        "Content-Length: " + "Vuelve a la aplicación.".encodeToByteArray().size
                    )
                )
                dismissed.await().invoke()
                withTimeout(3000) { returning.await() }
                assertFalse(result.isCompleted)
                assertFailsWith<java.io.IOException> { send(uri.authority, "nonce") }
                foreground.complete(Unit)
                assertNotNull(withTimeout(3000) { result.await() })
                assertNull(browser.openAndAwait(auth))
                assertFailsWith<java.io.IOException> { send(uri.authority, "nonce") }
            } finally {
                scope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `dismissing the Custom Tab cancels the pending callback promptly`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dismissed = CompletableDeferred<() -> Unit>()
            val browser =
                LoopbackBrowser(
                    scope,
                    { error("A cancelled tab must not wait for the foreground") },
                    { "Return" },
                ) { _, close ->
                    dismissed.complete(close)
                }
            val uri = URI(browser.redirectUri)
            val result =
                async(Dispatchers.IO) {
                    browser.openAndAwait(Secret("https://fixture.invalid/authorize?state=test"))
                }
            withTimeout(3000) { dismissed.await() }.invoke()
            assertNull(withTimeout(3000) { result.await() })
            assertTrue(uri.port > 0)
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
