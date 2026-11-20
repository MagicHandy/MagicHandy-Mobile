// Ported from internal/motion/{tempo,tempo_engine}.go at 1b80d72e (ADR 0071).
package com.magichandy.mobile.core.score

import kotlin.math.*
import kotlinx.serialization.json.*

internal enum class TempoShape {
    Steady,
    Ramp,
    Wave,
    Pulse,
    Pause,
}

internal data class TempoSection(
    val shape: TempoShape,
    val level: Int = 0,
    val low: Int = 0,
    val high: Int = 0,
    val period: Double = 0.0,
    val seconds: Double = 0.0,
) {
    init {
        require(
            period.isFinite() && seconds.isFinite() && (seconds == 0.0 || seconds in 1.0..1800.0)
        )
        when (shape) {
            TempoShape.Steady -> require(level in 1..100 && low == 0 && high == 0 && period == 0.0)
            TempoShape.Pause -> require(level == 0 && low == 0 && high == 0 && period == 0.0)
            TempoShape.Ramp ->
                require(level in 0..100 && low == 0 && high == 0 && period in 1.0..600.0)
            TempoShape.Wave,
            TempoShape.Pulse ->
                require(level == 0 && low in 0..99 && high in low + 1..100 && period in 2.0..120.0)
        }
    }

    fun sample(from: Double, millis: Double): Double {
        val value =
            when (shape) {
                TempoShape.Pause -> return 0.0
                TempoShape.Ramp -> return from + (level - from) * smooth(millis / (1000 * period))
                TempoShape.Steady -> level.toDouble()
                TempoShape.Wave,
                TempoShape.Pulse -> {
                    val phase = (1 - cos(2 * PI * (millis % (1000 * period)) / (1000 * period))) / 2
                    low +
                        (high - low) *
                            (if (shape == TempoShape.Pulse) smooth((phase - 0.25) * 2) else phase)
                }
            }
        return from + (value - from) * smooth(millis / 1000)
    }
}

private fun smooth(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

internal data class TempoScore(
    val sections: List<TempoSection>,
    val loop: Boolean,
    val description: JsonObject,
) {
    init {
        require(sections.size in 1..12)
        require(sections.dropLast(if (loop) 0 else 1).all { it.seconds >= 1 })
        require(!loop || sections.sumOf { it.seconds } >= 4)
    }

    companion object {
        fun steady(level: Int) =
            read(
                buildJsonObject {
                    if (level == 0) put("mode", "pause")
                    else {
                        put("mode", "steady")
                        put("level_percent", level)
                    }
                }
            )

        fun read(value: JsonObject): TempoScore {
            fun section(o: JsonObject): TempoSection {
                fun number(key: String) = o[key]?.jsonPrimitive?.double ?: 0.0
                return TempoSection(
                    TempoShape.entries.single {
                        it.name.equals(o.getValue("mode").jsonPrimitive.content, true)
                    },
                    o["level_percent"]?.jsonPrimitive?.int ?: 0,
                    o["min_percent"]?.jsonPrimitive?.int ?: 0,
                    o["max_percent"]?.jsonPrimitive?.int ?: 0,
                    number("period_seconds"),
                    number("seconds"),
                )
            }
            return when (value.getValue("mode").jsonPrimitive.content) {
                "sequence" ->
                    TempoScore(
                        value.getValue("sections").jsonArray.map { section(it.jsonObject) },
                        value["loop"]?.jsonPrimitive?.boolean ?: false,
                        value,
                    )
                "routine" -> {
                    val seed =
                        value["seed"]?.jsonPrimitive?.content?.toULong()
                            ?: kotlin.random.Random.nextLong().toULong()
                    val name = value.getValue("routine").jsonPrimitive.content
                    TempoScore(
                        tempoRoutine(name, seed),
                        name != "slow-build",
                        JsonObject(value + ("seed" to JsonPrimitive(seed.toString()))),
                    )
                }
                else -> TempoScore(listOf(section(value)), false, value)
            }
        }
    }
}

/**
 * Each complete loop is affine in its entry level. Long-running loops need no replay or history.
 */
internal class TempoPlan(val score: TempoScore, private val start: Double) {
    private val durations = score.sections.map { (it.seconds * 1000).roundToLong() }
    private val pass = durations.sum()

    private fun end(from: Double): Double =
        score.sections.indices.fold(from) { level, i ->
            score.sections[i].sample(level, durations[i].toDouble())
        }

    private val offset = if (score.loop) end(0.0) else 0.0
    private val carry = if (score.loop) ((end(100.0) - offset) / 100).coerceIn(0.0, 1.0) else 0.0

    fun level(millis: Long): Double {
        var at = millis.coerceAtLeast(0)
        var level = start
        if (score.loop && at >= pass) {
            val count = at / pass
            at %= pass
            level =
                if (carry == 1.0) start + count * offset
                else {
                    val r = carry.pow(count.toDouble())
                    start * r + offset * (1 - r) / (1 - carry)
                }
        }
        for ((i, section) in score.sections.withIndex()) {
            if (i == durations.lastIndex || at < durations[i])
                return section.sample(level, at.toDouble()).coerceIn(0.0, 100.0)
            level = section.sample(level, durations[i].toDouble())
            at -= durations[i]
        }
        return 0.0
    }
}
