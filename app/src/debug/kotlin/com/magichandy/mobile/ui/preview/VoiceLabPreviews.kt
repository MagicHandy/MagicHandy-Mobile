package com.magichandy.mobile.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.SpeechLevel
import com.magichandy.mobile.voicelab.EngineResult
import com.magichandy.mobile.voicelab.Fetch
import com.magichandy.mobile.voicelab.KitMode
import com.magichandy.mobile.voicelab.KitStatus
import com.magichandy.mobile.voicelab.LabActions
import com.magichandy.mobile.voicelab.LabEntry
import com.magichandy.mobile.voicelab.LabMode
import com.magichandy.mobile.voicelab.LabNote
import com.magichandy.mobile.voicelab.LabScript
import com.magichandy.mobile.voicelab.LabState
import com.magichandy.mobile.voicelab.Listening
import com.magichandy.mobile.voicelab.MeterBar
import com.magichandy.mobile.voicelab.MicSettings
import com.magichandy.mobile.voicelab.ParakeetBuild
import com.magichandy.mobile.voicelab.Reading
import com.magichandy.mobile.voicelab.Turn
import com.magichandy.mobile.voicelab.Vitals
import com.magichandy.mobile.voicelab.VoiceLabScreen
import com.magichandy.mobile.voicelab.scoreTranscript

// The voice test page, in test builds only (boards V-VoiceLab, V-VoiceLabListen, V-VoiceLabClip).

private val NoLabActions = object : LabActions {}

/** 2026-11-25 21:41 UTC, so the times on results are fixed. */
private const val EVENING = 1_795_729_260_000L

private val setUp =
    LabState(
        builds =
            mapOf(
                ParakeetBuild.Int8 to Fetch.Ready,
                ParakeetBuild.FourBit to Fetch.Running(286_000_000, 697_000_000),
                ParakeetBuild.Full to Fetch.Missing,
            ),
        kits = mapOf(KitMode.Basic to KitStatus.Ready, KitMode.Advanced to KitStatus.Downloadable),
        mic = MicSettings(deviceNearby = true),
        vitals = Vitals(battery = 87, memoryMb = 912),
    )

private val listening =
    setUp.copy(
        builds = setUp.builds + (ParakeetBuild.FourBit to Fetch.Ready),
        listening =
            Listening(
                since = 0,
                seconds = 1_214,
                bars =
                    listOf(0.32f, 0.3f, 0.33f, 0.31f).map { MeterBar(it) } +
                        listOf(0.45f, 0.62f, 0.7f, 0.66f, 0.58f, 0.64f, 0.52f).map {
                            MeterBar(it, isHot = true)
                        },
                speaking = true,
                partial = "keep that rhy",
                engines = "Parakeet int8 and ML Kit Basic",
                reading =
                    Reading(
                        micDb = -49f,
                        raises = true,
                        noiseDb = -78f,
                        gainDb = 24f,
                        probability = 0.93f,
                    ),
            ),
        vitals = Vitals(battery = 85, batteryAtStart = 87, minutes = 20, memoryMb = 1_640),
        entries =
            listOf<LabEntry>(
                Turn(
                    3,
                    EVENING,
                    2_400,
                    "1 m · device running · Standard audio · Silero 0.5",
                    null,
                    listOf(
                        EngineResult(
                            "Parakeet int8",
                            "Go a little slower, please.",
                            latencyMillis = 820,
                            processMillis = 504,
                            realTime = 0.21f,
                        ),
                        EngineResult(
                            "ML Kit Basic",
                            "go a little slower please",
                            latencyMillis = 640,
                        ),
                    ),
                    SpeechLevel(inputDb = -51f, gainDb = 24f),
                ),
                Turn(
                    2,
                    EVENING - 16_000,
                    600,
                    "1 m · device running · Standard audio · Silero 0.5",
                    null,
                    listOf(
                        EngineResult(
                            "Parakeet int8",
                            "Stop.",
                            latencyMillis = 550,
                            processMillis = 114,
                            realTime = 0.19f,
                            wouldStop = true,
                        ),
                        EngineResult("ML Kit Basic", "stop", latencyMillis = 480, wouldStop = true),
                    ),
                    SpeechLevel(inputDb = -47f, gainDb = 22f),
                ),
                LabNote(
                    1,
                    EVENING - 600_000,
                    "ML Kit stopped. Stopped: Google allows it only while the app is on screen.",
                ),
            ),
    )

private val clip: LabState
    get() {
        val said = LabScript.lines[2]
        fun result(engine: String, text: String, millis: Long, realTime: Float?) =
            EngineResult(
                engine,
                text,
                processMillis = millis,
                realTime = realTime,
                score = scoreTranscript(said, text),
            )
        return listening.copy(
            mode = LabMode.Clip,
            listening = null,
            builds = listening.builds,
            kits = mapOf(KitMode.Basic to KitStatus.Ready, KitMode.Advanced to KitStatus.Ready),
            checkedBuilds = setOf(ParakeetBuild.Int8, ParakeetBuild.FourBit),
            checkedKits = setOf(KitMode.Basic, KitMode.Advanced),
            line = 2,
            lineText = said,
            entries =
                listOf(
                    Turn(
                        4,
                        EVENING,
                        3_100,
                        "1 m · Standard audio",
                        said,
                        listOf(
                            result(
                                "Parakeet int8",
                                "Keep that written and go a deeper.",
                                713,
                                0.23f,
                            ),
                            result(
                                "Parakeet 4-bit",
                                "Keep that rhythm and go a little deeper.",
                                1_426,
                                0.46f,
                            ),
                            result(
                                "ML Kit Basic",
                                "keep that rhythmic and go a little deep",
                                950,
                                null,
                            ),
                            result(
                                "ML Kit Advanced",
                                "Keep that rhythm and go a little deeper.",
                                1_100,
                                null,
                            ),
                        ),
                        SpeechLevel(inputDb = -50f, gainDb = 24f),
                    )
                ),
        )
    }

/** Board V-VoiceLab: engines and microphone. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceLabSetUp(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { VoiceLabScreen(setUp, NoLabActions) }

/** Board V-VoiceLabListen: hands-free, with results and ML Kit stopping when the screen did. */
@Preview(widthDp = 390, heightDp = 1500)
@Composable
private fun VoiceLabListening() =
    InFrame(Palette.SteelAzure) { VoiceLabScreen(listening, NoLabActions) }

/** Board V-VoiceLabClip: one clip, every engine, scored against the line read out. */
@Preview(widthDp = 390, heightDp = 1500)
@Composable
private fun VoiceLabClip() = InFrame(Palette.SteelAzure) { VoiceLabScreen(clip, NoLabActions) }
