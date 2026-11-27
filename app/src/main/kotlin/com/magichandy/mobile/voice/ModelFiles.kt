package com.magichandy.mobile.voice

import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.core.api.VoiceModelStorage

/**
 * A file voice input downloads: where it comes from, its size and its SHA-256. [path] is where it
 * goes under the model folder. Every URL is pinned to a commit or release, and the checksum guards
 * against a file that changed anyway.
 */
data class ModelFile(val path: String, val url: String, val bytes: Long, val sha256: String)

/** The files a Parakeet transducer loads, with any its encoder keeps beside it. */
class ParakeetFiles(
    val encoder: ModelFile,
    val decoder: ModelFile,
    val joiner: ModelFile,
    val tokens: ModelFile,
    val extra: List<ModelFile> = emptyList(),
) {
    val all: List<ModelFile>
        get() = listOf(encoder) + extra + listOf(decoder, joiner, tokens)

    val bytes: Long
        get() = all.sumOf { it.bytes }
}

/** The files behind each speech model and speech detector. */
object ModelFiles {
    private const val INT8 =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/" +
            "ed5d745623472f97cfc2e6c2916ee248798fc5a8/"
    private const val FULL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3/resolve/" +
            "5fcc0122fe87e8365e339e467af90c67b746218f/"
    private const val DETECTORS =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val TOKENS_SHA256 =
        "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"

    val int8Encoder =
        ModelFile(
            "parakeet/encoder.int8.onnx",
            INT8 + "encoder.int8.onnx",
            652_282_294,
            "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479",
        )
    val int8Decoder =
        ModelFile(
            "parakeet/decoder.int8.onnx",
            INT8 + "decoder.int8.onnx",
            11_845_274,
            "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5",
        )
    val int8Joiner =
        ModelFile(
            "parakeet/joiner.int8.onnx",
            INT8 + "joiner.int8.onnx",
            6_355_277,
            "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98",
        )
    val tokens = ModelFile("parakeet/tokens.txt", INT8 + "tokens.txt", 93_939, TOKENS_SHA256)

    // The full-precision export keeps its weights in a second file beside the encoder.
    val fullEncoder =
        ModelFile(
            "parakeet-full/encoder.onnx",
            FULL + "encoder.onnx",
            41_838_346,
            "36b758364dfddce472b9a9f75bf6049bbd35c886d7927e3eda80d4ceb3348512",
        )
    val fullWeights =
        ModelFile(
            "parakeet-full/encoder.weights",
            FULL + "encoder.weights",
            2_435_420_160,
            "3af3f51af5f2d01dbbf5af47d42c7962a2c205f11004254bb4f2b979862f39a8",
        )
    val fullDecoder =
        ModelFile(
            "parakeet-full/decoder.onnx",
            FULL + "decoder.onnx",
            47_233_744,
            "caed31af1bb08edcc09a01a1c3cbb4d4eb5bfdb9c4e5f307012f84bbf5dbfdbc",
        )
    val fullJoiner =
        ModelFile(
            "parakeet-full/joiner.onnx",
            FULL + "joiner.onnx",
            25_286_331,
            "0f294e5ba9021a3d80e492de94abe4c116f0afce78452c7debb5dd07690d0340",
        )
    val fullTokens =
        ModelFile("parakeet-full/tokens.txt", FULL + "tokens.txt", 93_939, TOKENS_SHA256)

    val silero =
        ModelFile(
            "detectors/silero_vad.onnx",
            DETECTORS + "silero_vad.onnx",
            643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        )
    val ten =
        ModelFile(
            "detectors/ten-vad.onnx",
            DETECTORS + "ten-vad.onnx",
            332_211,
            "718cb7eef47e3cf5ddbe7e967a7503f46b8b469c0706872f494dfa921b486206",
        )

    /** The official sherpa-onnx int8 export. */
    val compactParakeet = ParakeetFiles(int8Encoder, int8Decoder, int8Joiner, tokens)

    /** Parakeet v3 as published. */
    val fullParakeet =
        ParakeetFiles(fullEncoder, fullDecoder, fullJoiner, fullTokens, listOf(fullWeights))
}

/** The speech detectors that end a turn, with the window each one reads at a time. */
val Detector.model: ModelFile
    get() =
        when (this) {
            Detector.Silero -> ModelFiles.silero
            Detector.Ten -> ModelFiles.ten
        }

val Detector.window: Int
    get() =
        when (this) {
            Detector.Silero -> 512
            Detector.Ten -> 256
        }

/** The Parakeet files behind this model. */
val VoiceModel.parakeet: ParakeetFiles
    get() =
        when (this) {
            VoiceModel.Compact -> ModelFiles.compactParakeet
            VoiceModel.Full -> ModelFiles.fullParakeet
        }

/** What listening with this model needs on the phone: Parakeet and the speech detector. */
fun VoiceModel.files(detector: Detector): List<ModelFile> = parakeet.all + detector.model

internal fun voiceModelStorage(): Map<VoiceModel, VoiceModelStorage> =
    VoiceModel.entries.associateWith { model ->
        val bytes = model.files(Detector.Silero).sumOf { it.bytes }
        VoiceModelStorage(bytes, bytes + ModelStore.HEADROOM_BYTES)
    }
