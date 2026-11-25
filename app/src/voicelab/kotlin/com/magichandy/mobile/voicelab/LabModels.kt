package com.magichandy.mobile.voicelab

import com.magichandy.mobile.voice.Detector
import com.magichandy.mobile.voice.ModelFile
import com.magichandy.mobile.voice.ModelFiles
import com.magichandy.mobile.voice.ParakeetFiles

/**
 * The files only the voice test downloads, beside voice input's own ([ModelFiles]). Every URL is
 * pinned to a commit or release, and the checksum guards against a file that changed anyway.
 */
object LabFiles {
    private const val NBITS =
        "https://huggingface.co/fyychs/parakeet-tdt-0.6b-v3-nbits-sherpa-onnx/resolve/" +
            "7eb0fe10eb354833cebfde598eafa669dc231753/"
    private const val INT8 =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/" +
            "ed5d745623472f97cfc2e6c2916ee248798fc5a8/"

    /** A community re-quantization: 4-bit in blocks of 32, which may keep distant speech. */
    val fourBitEncoder =
        ModelFile(
            "parakeet/encoder.q4.onnx",
            NBITS + "q4/encoder.onnx",
            679_469_428,
            "7ba528101e57769816216e35cbb3fb4fdeaac18b01eed54cc4e97d8fc01e43b5",
        )

    /** sherpa-onnx's English sample, to check the engines work without speaking. */
    val sample =
        ModelFile(
            "samples/en.wav",
            INT8 + "test_wavs/en.wav",
            184_608,
            "148b936b43ce7c546a866e64da059f0458aee2d65e617f16e9d94f06e8d99ed6",
        )
}

/** The Parakeet v3 builds the voice test compares. */
enum class ParakeetBuild(
    val title: String,
    val note: String,
    val short: String,
    val parakeet: ParakeetFiles,
) {
    Int8(
        "Parakeet v3 · int8",
        "Official sherpa-onnx build · voice input's Compact",
        "Parakeet int8",
        ModelFiles.compactParakeet,
    ),
    FourBit(
        "Parakeet v3 · 4-bit",
        "Community build · may keep distant speech",
        "Parakeet 4-bit",
        ParakeetFiles(
            LabFiles.fourBitEncoder,
            ModelFiles.int8Decoder,
            ModelFiles.int8Joiner,
            ModelFiles.tokens,
        ),
    ),
    Full(
        "Parakeet v3 · full precision",
        "Voice input's Full precision · about 3.6 GB of memory",
        "Parakeet full",
        ModelFiles.fullParakeet,
    );

    val files: List<ModelFile>
        get() = parakeet.all

    val bytes: Long
        get() = parakeet.bytes
}

/** The page's name for each speech detector. */
val Detector.title: String
    get() =
        when (this) {
            Detector.Silero -> "Silero"
            Detector.Ten -> "TEN"
        }
