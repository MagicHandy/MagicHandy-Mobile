package com.magichandy.mobile.platform

import com.magichandy.mobile.core.api.Secret
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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
                val browser = LoopbackBrowser(scope) { launched.complete(Unit) }
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
                        socket.getInputStream().bufferedReader().readLine()
                    }
                assertTrue(send("wrong.invalid", "nonce").contains("400"))
                assertTrue(send(uri.authority, "different").contains("400"))
                assertTrue(send(uri.authority, "nonce").contains("200"))
                assertNotNull(withTimeout(3000) { result.await() })
                assertNull(browser.openAndAwait(auth))
                assertFailsWith<java.io.IOException> { send(uri.authority, "nonce") }
            } finally {
                scope.cancel()
                Dispatchers.resetMain()
            }
        }
}
