package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

internal class ChatHistoryTest {
    @Test
    fun `a named chat connection retains the most recent 64 prior messages plus this request`() =
        runTest {
            var request = emptyList<TextMessage>()
            val f =
                DebugFixture(this) { deps ->
                    deps.copy(
                        provider = {
                            ModelProvider { messages, _ ->
                                request = messages
                                TextResult.Completed("Received.")
                            }
                        }
                    )
                }
            try {
                f.onboard()
                repeat(40) { f.send(UserIntent.SendMessage("Message $it")) }
                assertEquals(65, request.size)
                assertEquals("Message 7", request.first().text)
                assertEquals("Message 39", request.last().text)
            } finally {
                f.scope.cancel()
            }
        }
}
