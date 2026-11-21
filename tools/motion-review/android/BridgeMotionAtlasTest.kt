package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.LinearPoint
import com.magichandy.mobile.core.ports.LinearSampling
import com.magichandy.mobile.core.score.layeredScore
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Matched desktop cases driven through the actual linear fitting and normal HSP refill cadence. */
internal class BridgeMotionAtlasTest {
    @Test
    fun capture() {
        val reference = File(requireNotNull(System.getenv("MAGICHANDY_MOTION_REFERENCE")))
        val destination = File(requireNotNull(System.getenv("MAGICHANDY_LINEAR_CAPTURE")))
        val normal = LinearSampling(gapMillis = 100, steps = 100, preferredMillis = 180)
        val cases =
            mapOf(
                "full-025" to normal,
                "short-025" to normal,
                "short-100" to normal,
                "compound-060" to normal,
                "minimum-width-100" to normal.copy(gapMillis = 300),
            )
        val result = buildJsonObject {
            for ((name, policy) in cases) {
                val spec =
                    Json.parseToJsonElement(
                            File(reference, "handy_original-$name-ble.json").readText()
                        )
                        .jsonObject
                        .getValue("score")
                        .jsonObject
                        .layeredScore()
                val plan = requireNotNull(compileLayered(spec, HandyModel.Original))
                val timeline = ScoreTimeline(spec.speed, HandyModel.Original, PlaybackPosition())
                require(timeline.install(spec, plan, 0, 0))
                val linear = LinearSampler(timeline, policy)
                val buffered = BufferedSampler(timeline)
                val points = mutableListOf<LinearPoint>()
                val limits = Limits(Percent(100), Percent(0), Percent(100))
                val failure =
                    try {
                        while (linear.nextMillis <= 12000) {
                            var end = buffered.nextChunk().last().timeMillis
                            if (points.isEmpty() && end < 1500)
                                end = buffered.nextChunk().last().timeMillis
                            points += linear.through(end, limits)
                        }
                        null
                    } catch (e: IllegalArgumentException) {
                        e.message
                    }
                put(
                    name,
                    buildJsonObject {
                        put("hard_gap_ms", policy.intervalMillis)
                        put("preferred_ms", policy.preferredMillis)
                        put("steps", policy.steps)
                        put(
                            "wire",
                            buildJsonArray {
                                for (p in points) add(
                                    buildJsonArray {
                                        add(p.timeMillis)
                                        add(p.position)
                                    }
                                )
                            },
                        )
                        put(
                            "turns",
                            buildJsonArray {
                                timeline.knotTimes(0, 12001).sorted().forEach { add(it) }
                            },
                        )
                        if (failure != null) put("error", failure)
                    },
                )
            }
        }
        destination.parentFile.mkdirs()
        destination.writeText(result.toString())
    }
}
