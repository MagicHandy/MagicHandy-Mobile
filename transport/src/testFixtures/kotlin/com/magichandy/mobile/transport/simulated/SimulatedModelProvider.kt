package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Debug-only deterministic text replies. No credentials, inference runtime or network connection.
 */
public class SimulatedModelProvider(
    private val chunks: List<String> = listOf("I'm here. ", "We can take our time."),
    private val interval: Duration = 50.milliseconds,
) : ModelProvider {
    override suspend fun stream(
        messages: List<TextMessage>,
        preview: (String) -> Unit,
    ): TextResult {
        for (chunk in chunks) {
            delay(interval)
            preview(chunk)
        }
        return TextResult.Completed(chunks.joinToString(""))
    }
}
