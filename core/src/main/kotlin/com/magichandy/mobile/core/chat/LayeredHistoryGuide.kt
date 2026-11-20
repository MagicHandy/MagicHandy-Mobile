// Ported from internal/chat/{turn_score,requested_motion}.go at 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole

/** Request context for ordinary English local Layered position chat (no planner or paused run). */
internal data class LayeredHistoryGuide(
    val level: CapabilityLevel,
    val running: Boolean,
    val lowPercent: Int,
    val highPercent: Int,
    val speedPercent: Int,
    val minimumSpeed: Int,
    val maximumSpeed: Int,
) {
    fun addTo(prompt: AdmittedPrompt): List<TextMessage> {
        val full =
            when {
                level == CapabilityLevel.Simple && prompt.historyMessagesDropped > 0 ->
                    missingDefinition(prompt.historyMessagesDropped)
                level != CapabilityLevel.Advanced -> bandExample()
                running && prompt.messages.count { it.role != TextRole.Developer } - 1 > 64 ->
                    longHistoryGuide
                else -> return prompt.messages
            }
        return prompt.withOptionalGuide(
            full,
            if (level == CapabilityLevel.Advanced) "" else compactGuide,
        )
    }

    private fun missingDefinition(dropped: Int) =
        "Current score for this turn: outer band $lowPercent..$highPercent, overall speed $speedPercent. " +
            "$dropped earlier messages are outside this request. A named pattern can be applied only " +
            "if its actual settings appear in the available dialogue or an identified saved score. " +
            "If its definition is missing, use {\"action\":\"none\",\"edits\":[]} and ask for its settings. " +
            "When the corrected definition IS visible, apply both marks in a range group, full_band " +
            "in a geometry group and overall speed in a controls group. An independent instruction " +
            "about the motion playing now still applies. For a relative pace edit, put the signed " +
            "change requested this turn directly in change_by.speed_percent and omit every other control."

    private fun bandExample(): String {
        val speed =
            33.coerceAtLeast(minimumSpeed).let {
                if (maximumSpeed > 0) minOf(it, maximumSpeed) else it
            }
        val action = if (running) "update" else "start"
        return "First decide whether motion is requested NOW. Saving, correcting or reading a definition " +
            "is speech only. When asked to play a corrected definition, use its latest explicit numbers. " +
            "Full strokes between two marks touch both marks on EVERY pass. Send the specified outer " +
            "endpoints and full_band together, including when narrowing a full-stroke band. full_slider " +
            "sets 0 and 100. A mixture adds short strokes. Example for full passes from 17 to 69 at " +
            "overall speed $speed: {\"action\":\"$action\",\"edits\":[{\"range\":{\"min_percent\":17," +
            "\"max_percent\":69}},{\"geometry\":\"full_band\"},{\"controls\":{\"speed_percent\":$speed}}]," +
            "\"reply\":\"Applying the requested sweep.\"} A speed-only edit uses only speed. A shift " +
            "of the whole interval moves both current endpoints by the requested amount."
    }
}

private const val compactGuide =
    "Notes/queries: none. Apply: both marks, full_band, speed. Missing: ask."

private const val longHistoryGuide =
    "Use the latest user message to decide whether to speak or act. Earlier definitions and " +
        "corrections are reference material. For a read-back or question about an earlier pattern, " +
        "return {\"action\":\"none\",\"edits\":{},\"reply\":\"...\"} with the requested facts only in the " +
        "reply; the current pattern keeps playing. When the user instead asks to APPLY a named pattern, " +
        "encode the latest corrected values in edits. For example, applying a full sweep from 12 to " +
        "72 at speed 28 while running uses {\"action\":\"update\",\"edits\":{\"composition\":\"full_band\"," +
        "\"controls\":{\"max_percent\":{\"op\":\"set\",\"value\":72},\"min_percent\":{\"op\":\"set\",\"value\":12}," +
        "\"speed_percent\":{\"op\":\"set\",\"value\":28}}},\"reply\":\"Applying the requested sweep.\"} Use the " +
        "actual requested settings. Overall speed is separate from a phrase group's relative rate."
