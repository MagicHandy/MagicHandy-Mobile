package com.magichandy.mobile.voicelab

import com.magichandy.mobile.voice.MicSource
import com.magichandy.mobile.voice.SAMPLE_RATE
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The page's name for each microphone path. Recording itself is voice input's own
 * ([com.magichandy.mobile.voice.MicCapture]), so the page measures the code the app ships.
 */
val MicSource.title: String
    get() =
        when (this) {
            MicSource.Standard -> "Standard"
            MicSource.Speech -> "Speech"
            MicSource.EchoCancel -> "Echo cancel"
            MicSource.Raw -> "Raw"
        }

/** Writes 16 kHz mono 16-bit [pcm] as a WAV file, which ML Kit decodes by itself. */
fun writeWav(file: File, pcm: ShortArray) {
    val dataBytes = pcm.size * Short.SIZE_BYTES
    val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(WAV_HEADER_BYTES - 8 + dataBytes)
    buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(SAMPLE_RATE)
    buffer.putInt(SAMPLE_RATE * Short.SIZE_BYTES)
    buffer.putShort(Short.SIZE_BYTES.toShort())
    buffer.putShort(Short.SIZE_BITS.toShort())
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataBytes)
    for (sample in pcm) buffer.putShort(sample)
    file.parentFile?.mkdirs()
    file.writeBytes(buffer.array())
}

/**
 * Reads a mono 16-bit WAV file as 16 kHz audio, resampling another rate, such as sherpa-onnx's 24
 * kHz samples. Throws [IOException] for stereo or other sample formats.
 */
fun readWav(file: File): ShortArray {
    DataInputStream(file.inputStream().buffered()).use { input ->
        val header = ByteArray(12)
        input.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") throw IOException("Not a WAV file")
        var format: ByteBuffer? = null
        while (true) {
            val chunkHeader = ByteArray(8)
            input.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size =
                ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                    0xffffffffL
            val body = ByteArray(size.toInt())
            input.readFully(body)
            if (size % 2 == 1L) input.skipBytes(1)
            when (id) {
                "fmt " -> format = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                "data" -> {
                    val fmt = format ?: throw IOException("WAV data before its format")
                    val channels = fmt.getShort(2).toInt()
                    val rate = fmt.getInt(4)
                    val bits = fmt.getShort(14).toInt()
                    if (channels != 1 || bits != Short.SIZE_BITS || rate <= 0) {
                        throw IOException("Needs mono 16-bit audio")
                    }
                    val samples =
                        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val pcm = ShortArray(samples.remaining()).also { samples.get(it) }
                    return resample(pcm, rate, SAMPLE_RATE)
                }
            }
        }
    }
}

/**
 * [pcm] at [from] Hz, resampled to [to] Hz by straight-line interpolation: plenty for checking an
 * engine on a sample recording, though not a studio resampler.
 */
fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
    if (from == to || pcm.isEmpty()) return pcm
    val length = (pcm.size.toLong() * to / from).toInt().coerceAtLeast(1)
    return ShortArray(length) { index ->
        val position = index.toDouble() * from / to
        val before = position.toInt().coerceAtMost(pcm.lastIndex)
        val after = (before + 1).coerceAtMost(pcm.lastIndex)
        val fraction = position - before
        (pcm[before] + (pcm[after] - pcm[before]) * fraction).toInt().toShort()
    }
}

/** [pcm] as little-endian bytes, as ML Kit reads a stream. */
fun toBytes(pcm: ShortArray): ByteArray {
    val buffer = ByteBuffer.allocate(pcm.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (sample in pcm) buffer.putShort(sample)
    return buffer.array()
}

private const val WAV_HEADER_BYTES = 44
