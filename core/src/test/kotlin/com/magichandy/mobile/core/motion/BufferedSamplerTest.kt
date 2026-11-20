package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.fixtures.fixtureJson
import kotlin.test.assertEquals
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class BufferedSamplerTest {
    @TestFactory
    fun `authored atlas inputs reproduce every Go period and buffered frame`(): List<DynamicTest> =
        fixtureJson("manifest.json")
            .jsonObject
            .getValue("cases")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("role").jsonPrimitive.content == "motion-atlas" }
            .map { case ->
                DynamicTest.dynamicTest(case.getValue("case_id").jsonPrimitive.content) {
                    val data = fixtureJson(case.getValue("path").jsonPrimitive.content).jsonObject
                    fun points(field: String) =
                        data.getValue(field).jsonArray.map { value ->
                            val point = value.jsonObject
                            CurvePoint(
                                point.getValue("time_ms").jsonPrimitive.long,
                                point.getValue("position_percent").jsonPrimitive.double,
                            )
                        }
                    val plan =
                        MotionPlan(
                            points("authored"),
                            data.getValue("speed_percent").jsonPrimitive.int,
                            HandyModel.Original,
                        )
                    assertEquals(data.getValue("period_ms").jsonPrimitive.long, plan.periodMillis)
                    val sampler = BufferedSampler(plan)
                    val actual = buildList {
                        while (sampler.nextSampleMillis < minOf(12000L, plan.periodMillis)) addAll(
                            sampler.nextChunk()
                        )
                    }
                    assertEquals(
                        points("frames").map {
                            MotionFrame(it.timeMillis, Percent(it.position.toInt()))
                        },
                        actual,
                    )
                }
            }
}
