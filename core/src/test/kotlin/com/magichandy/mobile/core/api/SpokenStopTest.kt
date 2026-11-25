package com.magichandy.mobile.core.api

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** A spoken Stop is caught however it's padded or repeated, and "don't stop" never is. */
internal class SpokenStopTest {
    @Test
    fun `every typed Stop is a spoken Stop too`() {
        listOf("Stop.", "Stop right now", "That's enough", "Emergency stop", "Pare o movimento")
            .forEach { assertTrue(isSpokenStop(it), it) }
    }

    @Test
    fun `a repeated Stop stops, with or without punctuation`() {
        listOf(
                "Stop, stop, stop.",
                "Stop stop stop",
                "STOP! STOP!",
                "Stop. Stop it!",
                "stop stop it",
            )
            .forEach { assertTrue(isSpokenStop(it), it) }
    }

    @Test
    fun `fillers around a Stop don't hide it`() {
        listOf(
                "Okay, stop.",
                "No, stop!",
                "No stop",
                "Wait, stop now.",
                "Hey stop it please",
                "Okay stop now",
                "Stop, please.",
            )
            .forEach { assertTrue(isSpokenStop(it), it) }
    }

    @Test
    fun `the ways people say Stop aloud stop`() {
        listOf("Turn it off!", "Make it stop.", "Stop that.", "Halt.", "Stop moving.").forEach {
            assertTrue(isSpokenStop(it), it)
        }
    }

    @Test
    fun `a clear Stop in one clause stops even when more follows`() {
        assertTrue(isSpokenStop("Stop. Actually, keep going."))
        assertTrue(isSpokenStop("I said stop!"))
    }

    @Test
    fun `don't stop and other negations never stop`() {
        listOf(
                "Don't stop.",
                "Don't. Stop.",
                "Don't, stop!",
                "Please don't stop.",
                "Never stop.",
                "Do not stop",
                "Never. Stop.",
            )
            .forEach { assertFalse(isSpokenStop(it), it) }
    }

    @Test
    fun `asking for something else that mentions stop goes to chat`() {
        listOf(
                "Stop teasing me.",
                "I want you to stop soon.",
                "When should we stop?",
                "Keep going until I say stop",
                "Go a little slower, please.",
            )
            .forEach { assertFalse(isSpokenStop(it), it) }
    }

    @Test
    fun `ambiguous words stop only when nothing else is said`() {
        listOf("Pause.", "Enough!", "Enough. Enough.", "Para.", "¡Basta!", "Chega!").forEach {
            assertTrue(isSpokenStop(it), it)
        }
        listOf(
                "Pause, then go deeper.",
                "Enough, go faster.",
                "Para mí está bien.",
                "The end of the stroke.",
            )
            .forEach { assertFalse(isSpokenStop(it), it) }
    }

    @Test
    fun `Spanish and Portuguese Stops stop, and their negations don't`() {
        listOf(
                "¡Detente!",
                "Para ya.",
                "Detén el movimiento.",
                "Pare!",
                "Pare agora.",
                "Desliga!",
                "Não, pare!",
            )
            .forEach { assertTrue(isSpokenStop(it), it) }
        listOf("No pares.", "Não pare.", "No te detengas.").forEach {
            assertFalse(isSpokenStop(it), it)
        }
    }

    @Test
    fun `nothing heard is no Stop`() {
        listOf("", "   ", "…", "Mm.").forEach { assertFalse(isSpokenStop(it), it) }
    }
}
