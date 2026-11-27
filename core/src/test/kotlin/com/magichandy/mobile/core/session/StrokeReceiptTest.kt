package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.CommandReceipt
import com.magichandy.mobile.core.api.ControlKind
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.StrokeGroupReceipt
import com.magichandy.mobile.core.api.ValueChange
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.PhraseGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

internal class StrokeReceiptTest {
    private val sweep = LayeredScore(low = 0, high = 100, speed = 30, floor = 100)
    private val phrase =
        sweep.copy(
            floor = 35,
            layers = emptyList(),
            groups = listOf(PhraseGroup(0, 100, 1, 30), PhraseGroup(0, 35, 2, 30)),
        )
    private val limits = Limits(Percent(80), Percent(20), Percent(80))

    @Test
    fun `adding and removing two base rebounds records their actual limited range at unchanged pace`() {
        val entering = assertNotNull(phrase.strokeReceipt(sweep, 30, limits))
        assertEquals(emptyList(), entering.changes)
        assertEquals(
            listOf(StrokeGroupReceipt(1, 20, 80), StrokeGroupReceipt(2, 20, 41)),
            entering.pattern?.groups,
        )
        val leaving = assertNotNull(sweep.strokeReceipt(phrase, 30, limits))
        assertEquals(emptyList(), leaving.changes)
        assertEquals(listOf(StrokeGroupReceipt(1, 20, 80)), leaving.pattern?.groups)
        assertNull(sweep.strokeReceipt(sweep, 30, limits))
        assertNull(sweep.copy(seed = 42u).strokeReceipt(sweep, 30, limits))
        assertEquals(entering, Json.decodeFromString<CommandReceipt>(Json.encodeToString(entering)))
    }

    @Test
    fun `speed changes use the accepted pace and legacy receipts still load`() {
        val faster = assertNotNull(sweep.copy(speed = 95).strokeReceipt(sweep, 30, limits))
        assertEquals(listOf(ValueChange(ControlKind.Pace, 30, 80)), faster.changes)
        assertNull(faster.pattern)
        val old =
            Json.decodeFromString<CommandReceipt>(
                """{"changes":[{"control":"Pace","from":30,"to":30}]}"""
            )
        assertNull(old.pattern)
    }
}
