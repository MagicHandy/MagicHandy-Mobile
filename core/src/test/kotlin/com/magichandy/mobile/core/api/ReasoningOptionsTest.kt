package com.magichandy.mobile.core.api

import com.magichandy.mobile.core.api.ReasoningEffort.ExtraHigh
import com.magichandy.mobile.core.api.ReasoningEffort.High
import com.magichandy.mobile.core.api.ReasoningEffort.Low
import com.magichandy.mobile.core.api.ReasoningEffort.Max
import com.magichandy.mobile.core.api.ReasoningEffort.Medium
import com.magichandy.mobile.core.api.ReasoningEffort.Minimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a saved reasoning choice settles on a model (docs/openrouter-design.md §4.3). */
internal class ReasoningOptionsTest {
    private val optional = ReasoningOptions(listOf(Low, High, Max), isRequired = false, High)
    private val required = ReasoningOptions(listOf(Low, High, Max), isRequired = true, Max)
    private val onOff = ReasoningOptions(emptyList(), isRequired = false)

    @Test
    fun `the app offers levels up to High, or a model's lowest when it lists only higher ones`() {
        assertEquals(listOf(Low, High), optional.offered)
        assertEquals(
            listOf(Minimal, Low, Medium, High),
            ReasoningOptions(ReasoningEffort.entries, isRequired = false).offered,
        )
        assertEquals(listOf(ExtraHigh), ReasoningOptions(listOf(ExtraHigh, Max), true).offered)
        assertEquals(emptyList(), onOff.offered)
    }

    @Test
    fun `off stays off unless the model requires reasoning, which starts at its lowest level`() {
        assertEquals(ReasoningChoice(false, null), optional.settle(isOn = false, level = High))
        assertEquals(ReasoningChoice(true, Low), required.settle(isOn = false, level = null))
        assertEquals(
            ReasoningChoice(true, null),
            ReasoningOptions(emptyList(), true).settle(false, null),
        )
    }

    @Test
    fun `a level the model doesn't offer moves to the nearest one, the lower on a tie`() {
        assertEquals(ReasoningChoice(true, Low), optional.settle(isOn = true, level = Medium))
        assertEquals(ReasoningChoice(true, High), optional.settle(isOn = true, level = Max))
        assertEquals(ReasoningChoice(true, Low), optional.settle(isOn = true, level = Minimal))
        assertEquals(ReasoningChoice(true, High), optional.settle(isOn = true, level = High))
    }

    @Test
    fun `on without a level lets the model choose, and a model without levels takes none`() {
        assertEquals(ReasoningChoice(true, null), optional.settle(isOn = true, level = null))
        assertNull(onOff.settle(isOn = true, level = High).level)
    }

    @Test
    fun `100 replies cost 4,000 tokens in and 500 out each, and free means both prices are zero`() {
        val model = RouterModel("a/b", "B", 1000, null, 36_000, 500_000)
        assertEquals(39_400L, model.hundredRepliesMicros)
        assertNull(model.copy(inputMicrosPerMillion = null).hundredRepliesMicros)
        assertEquals(true, model.copy(inputMicrosPerMillion = 0, outputMicrosPerMillion = 0).isFree)
    }
}
