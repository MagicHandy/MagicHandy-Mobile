package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.fixtures.fixtureJson
import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.SweepDirection
import com.magichandy.mobile.core.score.layeredScore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class ContinuousAtlasTest {
    @TestFactory
    fun `Layered fields counted motifs and regional travel reproduce desktop wire output`() =
        listOf("field", "counted", "regional").map { id ->
            DynamicTest.dynamicTest(id) {
                val data = fixtureJson("motion/layered/$id.json").jsonObject
                compare(
                    data,
                    assertNotNull(
                        compileLayered(
                            data.getValue("score").jsonObject.layeredScore(),
                            HandyModel.Original,
                        )
                    ),
                )
            }
        }

    @TestFactory
    fun `semantic gesture scores reproduce the desktop period and every wire point`() =
        fixtureJson("manifest.json")
            .jsonObject
            .getValue("cases")
            .jsonArray
            .map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "creative-atlas" }
            .map { entry ->
                DynamicTest.dynamicTest(entry.getValue("case_id").jsonPrimitive.content) {
                    val data = fixtureJson(entry.getValue("path").jsonPrimitive.content).jsonObject
                    val model =
                        HandyModel.calibration(
                            listOf(
                                data
                                    .getValue("settings")
                                    .jsonObject
                                    .getValue("handy_model")
                                    .jsonPrimitive
                                    .content
                            )
                        )
                    val score = data.getValue("score").jsonObject
                    val plan =
                        assertNotNull(
                            if ("gesture" in score) compileGesture(gesture(score), model)
                            else {
                                val points =
                                    OriginalCreativeComparison(
                                            score.num("center_percent"),
                                            score.num("span_percent"),
                                            score.num("span_min_percent"),
                                            score
                                                .getValue("phrase_seed")
                                                .jsonPrimitive
                                                .long
                                                .toUInt(),
                                            score.num("variation_percent"),
                                        )
                                        .points()
                                compileDynamic(points, data.num("speed_percent"), model)
                            }
                        )
                    compare(data, plan)
                }
            }

    private fun gesture(score: JsonObject): GestureScore {
        val g = score.getValue("gesture").jsonObject
        fun number(key: String) = g.getValue(key).jsonPrimitive.int
        return GestureScore(
            score.num("min_percent"),
            score.num("max_percent"),
            score.num("speed_percent"),
            score.num("memory_cycles"),
            score.getValue("seed").jsonPrimitive.long.toUInt(),
            score["loop_cycles"]?.jsonPrimitive?.int ?: 64,
            number("focus_percent"),
            number("focus_width_percent"),
            number("focus_mix_percent"),
            number("focus_roam_percent"),
            SweepDirection.valueOf(
                g.getValue("faster_direction").jsonPrimitive.content.replaceFirstChar {
                    it.uppercaseChar()
                }
            ),
            number("contrast_percent"),
            number("inertia_percent"),
            number("rebound_count"),
            number("rebound_decay_percent"),
            number("variation_percent"),
        )
    }

    private fun JsonObject.num(name: String) = getValue(name).jsonPrimitive.int

    private fun compare(data: JsonObject, plan: ContinuousPlan) {
        assertEquals(data.getValue("period_ms").jsonPrimitive.long, plan.periodMillis, "period")
        val sampler = BufferedSampler(plan)
        val actual = buildList {
            while (sampler.nextSampleMillis < minOf(plan.periodMillis, 12000)) addAll(
                sampler.nextChunk()
            )
        }
        val expected = data.getValue("frames").jsonArray
        for (i in 0 until minOf(actual.size, expected.size)) {
            val point = expected[i].jsonObject
            assertEquals(
                point.getValue("time_ms").jsonPrimitive.long,
                actual[i].timeMillis,
                "frame $i time",
            )
            assertEquals(
                point.num("position_percent"),
                actual[i].position.value,
                "frame $i position",
            )
        }
        assertEquals(expected.size, actual.size, "frame count")
    }
}
