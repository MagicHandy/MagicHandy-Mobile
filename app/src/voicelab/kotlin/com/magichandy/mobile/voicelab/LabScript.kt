package com.magichandy.mobile.voicelab

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The lines to read out in One clip mode: requests the app hears during a session, ways of saying
 * Stop, phrases that mustn't stop anything, and ordinary conversation.
 */
object LabScript {
    val lines: List<String> =
        listOf(
            "Go a little slower, please.",
            "Stop.",
            "Keep that rhythm and go a little deeper.",
            "Hold it there for a moment.",
            "A bit faster now.",
            "Stop right now.",
            "Can you make the strokes longer?",
            "That feels really good, keep going.",
            "Pause for a second.",
            "Speed it up slowly over the next minute.",
            "Only use the top half of the stroke.",
            "Stop, stop, stop.",
            "Tease me a little before you go faster.",
            "Don't stop.",
            "Make it shorter and quicker at the tip.",
            "Slow down, I'm getting close.",
            "Let's take a break.",
            "Can you switch to a gentler style?",
            "Turn off Autopilot.",
            "Hey, how was your day?",
            "Tell me what you're going to do next.",
            "Edge me for a few minutes.",
            "Go back to the rhythm from before.",
            "Never mind, keep going exactly like that.",
        )
}

/** The results as plain text to share, with the phone and settings they were measured with. */
object LabReport {
    fun build(state: LabState, versionName: String, traces: List<String> = emptyList()): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val text = StringBuilder()
        text.appendLine("MagicHandy voice test " + versionName)
        text.appendLine(
            Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
        )
        text.appendLine("Shared " + time.format(Date()))
        val vitals = state.vitals
        text.appendLine(
            "Battery " +
                (vitals.batteryAtStart?.let { "$it% → " } ?: "") +
                (vitals.battery?.let { "$it%" } ?: "?") +
                (vitals.minutes?.let { " over $it min" } ?: "") +
                " · temperature " +
                vitals.thermal +
                " · memory " +
                (vitals.memoryMb?.let { "$it MB" } ?: "?")
        )
        text.appendLine(
            "Now: detector " +
                state.mic.detectorTag +
                ", silence " +
                state.mic.silenceSeconds +
                " s · quiet speech " +
                (if (state.mic.raise) "raised" else "not raised") +
                " · ML Kit language " +
                state.language.title
        )
        for (entry in state.entries.reversed()) {
            text.appendLine()
            when (entry) {
                is LabNote -> text.appendLine(time.format(Date(entry.at)) + " · " + entry.text)
                is Turn -> {
                    text.appendLine(
                        time.format(Date(entry.at)) +
                            " · " +
                            entry.speechMillis +
                            " ms of speech" +
                            (entry.level?.let { " " + it.describe() } ?: "") +
                            " · " +
                            entry.tags
                    )
                    entry.reference?.let { text.appendLine("Said: " + it) }
                    for (result in entry.results) text.appendLine(line(result))
                }
            }
        }
        if (traces.isNotEmpty()) {
            text.appendLine()
            text.appendLine("Errors, newest last:")
            traces.forEach { text.appendLine(it) }
        }
        return text.toString()
    }

    private fun line(result: EngineResult): String {
        val figures =
            listOfNotNull(
                result.score?.let { "${it.errors} of ${it.words} wrong" },
                result.latencyMillis?.let { "latency $it ms" },
                result.processMillis?.let { "work $it ms" },
                result.realTime?.let { "×" + "%.2f".format(Locale.ROOT, it) },
                "would stop".takeIf { result.wouldStop },
            )
        val body = result.error ?: result.text?.let { "“$it”" } ?: "(no result)"
        return "  " + result.engine + ": " + body + figures.joinToString("") { " · $it" }
    }
}
