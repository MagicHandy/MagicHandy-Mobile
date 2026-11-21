package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.score.TempoPlan
import com.magichandy.mobile.core.score.TempoScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class TempoPlanTest {
    @TestFactory
    fun `each routine equals the seeded Go sampler across later passes`() =
        listOf("slow-build", "swells", "bursts", "stairs", "edge-and-release", "wandering").map { id
            ->
            DynamicTest.dynamicTest(id) {
                val input =
                    requireNotNull(javaClass.getResourceAsStream("/motion/tempo/$id.json"))
                        .bufferedReader()
                        .use { Json.parseToJsonElement(it.readText()).jsonObject }
                val score = TempoScore.read(input.getValue("score").jsonObject)
                val plan = TempoPlan(score, input.getValue("from").jsonPrimitive.double)
                val expected = input.getValue("levels").jsonArray
                for ((i, t) in input.getValue("times").jsonArray.withIndex()) assertEquals(
                    expected[i].jsonPrimitive.double,
                    plan.level(t.jsonPrimitive.long),
                    1e-8,
                )
            }
        }

    @Test
    fun `pause is immediate and a ramp retarget starts at the previous commanded level`() {
        val wave =
            TempoPlan(
                TempoScore.read(
                    Json.parseToJsonElement(
                            """{"mode":"wave","min_percent":20,"max_percent":70,"period_seconds":4}"""
                        )
                        .jsonObject
                ),
                40.0,
            )
        val before = wave.level(1800)
        val ramp =
            TempoPlan(
                TempoScore.read(
                    Json.parseToJsonElement(
                            """{"mode":"ramp","level_percent":80,"period_seconds":4}"""
                        )
                        .jsonObject
                ),
                before,
            )
        assertEquals(before, ramp.level(0))
        assertEquals(80.0, ramp.level(4000))
        assertEquals(0.0, TempoPlan(TempoScore.steady(0), 80.0).level(0))
    }
}
