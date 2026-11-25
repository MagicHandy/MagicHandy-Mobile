package com.magichandy.mobile.voice

import kotlin.math.log10

/** Every part of voice input hears 16 kHz mono audio. */
const val SAMPLE_RATE = 16_000

/** The level reported for digital silence, in dBFS. */
const val SILENCE_DB = -100f

internal const val FULL_SCALE = 32_768f

/** The RMS level of [samples] in dBFS, from [SILENCE_DB] up to 0 at full scale. */
fun levelDb(samples: FloatArray): Float {
    if (samples.isEmpty()) return SILENCE_DB
    var sum = 0.0
    for (sample in samples) sum += sample * sample
    val meanSquare = sum / samples.size
    if (meanSquare <= 0.0) return SILENCE_DB
    return (DB_PER_BEL * log10(meanSquare)).toFloat().coerceAtLeast(SILENCE_DB)
}

/** A chunk's loudness from 0 (−60 dBFS or quieter) to 1 (full scale), for a meter. */
fun loudness(samples: FloatArray): Float =
    ((levelDb(samples) + METER_FLOOR_DB) / METER_FLOOR_DB).coerceIn(0f, 1f)

/** [pcm] as floats from -1 to 1, as sherpa-onnx takes audio. */
fun toFloats(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / FULL_SCALE }

/** [samples] from -1 to 1 as 16-bit audio, held at full scale. */
fun toPcm(samples: FloatArray): ShortArray =
    ShortArray(samples.size) {
        (samples[it] * FULL_SCALE)
            .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            .toInt()
            .toShort()
    }

private const val DB_PER_BEL = 10
private const val METER_FLOOR_DB = 60f
