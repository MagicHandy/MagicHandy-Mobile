package com.magichandy.mobile.voice

/**
 * The last [capacity] samples heard, addressed by their position since listening started. It lives
 * only in memory: voice input never writes audio anywhere.
 */
class AudioHistory(private val capacity: Int) {
    private val ring = FloatArray(capacity)

    /** How many samples have been added in all. */
    var size = 0L
        private set

    fun add(samples: FloatArray) {
        for (sample in samples) {
            ring[(size % capacity).toInt()] = sample
            size++
        }
    }

    /** The samples from [from] up to [to], or the part of them still held. */
    fun slice(from: Long, to: Long): FloatArray {
        val first = maxOf(from, size - capacity, 0L)
        val last = minOf(to, size)
        if (last <= first) return FloatArray(0)
        return FloatArray((last - first).toInt()) { ring[((first + it) % capacity).toInt()] }
    }
}
