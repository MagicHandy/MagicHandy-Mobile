package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.SweepDirection
import com.magichandy.mobile.core.score.layeredScore
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Inert review of the same compiler, changing score timeline and buffered sampler as playback. */
internal class HandyMotionAtlasTest {
    @Test
    fun capture() {
        val root = File(requireNotNull(System.getenv("MAGICHANDY_MOTION_REFERENCE")))
        val destination = File(requireNotNull(System.getenv("MAGICHANDY_MOTION_CAPTURE")))
        destination.mkdirs()
        val inputs = requireNotNull(root.listFiles()).filter { it.extension == "json" }.sorted()
        require(inputs.isNotEmpty())
        for (input in inputs) {
            val data = Json.parseToJsonElement(input.readText()).jsonObject
            val only = System.getenv("MAGICHANDY_MOTION_ONLY")
            if (!only.isNullOrBlank() && data.getValue("name").jsonPrimitive.content != only)
                continue
            val result =
                try {
                    captureCase(data)
                } catch (failure: IllegalArgumentException) {
                    buildJsonObject { put("error", failure.message) }
                }
            File(destination, input.name).writeText(result.toString())
        }
    }

    private fun captureCase(input: JsonObject): JsonObject {
        val model = HandyModel.calibration(listOf(input.getValue("model").jsonPrimitive.content))
        val spec = input.getValue("score").jsonObject
        val score = if ("gesture" in spec) gesture(spec) else spec.layeredScore()
        val plan = requireNotNull(compileScore(score, model))
        val timeline = ScoreTimeline(score.speed, model, PlaybackPosition())
        val manual = input.getValue("name").jsonPrimitive.content == "manual"
        if (!manual) require(timeline.install(score, plan, 0, 0))
        val period =
            if (manual)
                timeline.knotTimes(1, 120000).sorted().first { at ->
                    val phase = timeline.positionAt(at).phase
                    minOf(phase, 1.0 - phase) < 0.0002
                }
            else plan.periodMillis
        val route =
            if (input.getValue("route").jsonPrimitive.content == "cloud") ConnectionKind.HandyCloud
            else ConnectionKind.Bluetooth
        val sampler =
            timeline.bufferedSampler(
                listOf(DeviceRef(DeviceId("atlas"), "Fixture", DeviceFamily.Handy, route))
            )
        val frames = mutableListOf<MotionFrame>()
        val chunks = mutableListOf<Int>()
        while (sampler.nextSampleMillis <= 12000) {
            val chunk = sampler.nextChunk()
            chunks.add(chunk.size)
            frames += chunk
        }
        var maxError = 0.0
        var peakSpeed = 0.0
        for ((a, b) in frames.zipWithNext()) {
            if (a.timeMillis >= 12000) break
            val slope =
                (b.position.value - a.position.value).toDouble() / (b.timeMillis - a.timeMillis)
            peakSpeed = maxOf(peakSpeed, abs(slope) * 1000)
            for (at in a.timeMillis..minOf(12000, b.timeMillis)) {
                val encoded = a.position.value + (at - a.timeMillis) * slope
                maxError = maxOf(maxError, abs(encoded - timeline.sampleAt(at).position))
            }
        }
        return buildJsonObject {
            put("period", period)
            put("max_error", maxError)
            put("peak_wire_speed", peakSpeed)
            put("chunks", buildJsonArray { chunks.forEach { add(it) } })
            put(
                "turns",
                buildJsonArray { timeline.knotTimes(0, 12001).sorted().forEach { add(it) } },
            )
            put(
                "wire",
                buildJsonArray {
                    for (p in frames) add(
                        buildJsonArray {
                            add(p.timeMillis)
                            add(p.position.value)
                        }
                    )
                },
            )
            put(
                "loop",
                buildJsonArray {
                    val times = (0..1600).map { (period * it / 1600.0).roundToLong() }.distinct()
                    for (at in times) {
                        val state =
                            if (!manual) plan.stateAt(at)
                            else {
                                val p = timeline.sampleAt(at).position
                                val l = timeline.sampleAt((at + period - 1) % period).position
                                val r = timeline.sampleAt((at + 1) % period).position
                                CurveState(p, (r - l) / 2, r - 2 * p + l)
                            }
                        add(
                            buildJsonArray {
                                add(at)
                                add(state.position)
                                add(state.velocity * 1000)
                                add(state.acceleration * 1e6)
                            }
                        )
                    }
                },
            )
        }
    }

    private fun gesture(spec: JsonObject): GestureScore {
        val g = spec.getValue("gesture").jsonObject
        fun JsonObject.n(k: String, fallback: Int = 0) = this[k]?.jsonPrimitive?.int ?: fallback
        return GestureScore(
            spec.n("min_percent"),
            spec.n("max_percent"),
            spec.n("speed_percent"),
            spec.n("memory_cycles"),
            spec.getValue("seed").jsonPrimitive.long.toUInt(),
            spec.n("loop_cycles", 64),
            g.n("focus_percent"),
            g.n("focus_width_percent"),
            g.n("focus_mix_percent"),
            g.n("focus_roam_percent"),
            SweepDirection.valueOf(
                (g["faster_direction"]?.jsonPrimitive?.content ?: "even").replaceFirstChar {
                    it.uppercaseChar()
                }
            ),
            g.n("contrast_percent"),
            g.n("inertia_percent"),
            g.n("rebound_count"),
            g.n("rebound_decay_percent", 60),
            g.n("variation_percent"),
        )
    }
}
