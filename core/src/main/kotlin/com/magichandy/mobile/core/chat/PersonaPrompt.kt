// Ported from internal/chat/{prompts,prompt_localization,reply_length,autopilot_service}.go at
// 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ReplyLength
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Only the desktop's two wording sections can be replaced. All other sections are opaque. */
internal object PersonaPrompt {
    @Serializable private data class Voice(val identity: String, val check: String)

    @Serializable
    private data class Vocabulary(
        val identityHeading: String,
        val checkHeading: String,
        val voices: Map<String, Voice>,
        val styles: Map<String, String>,
        val profiles: Map<String, String>,
        val detailed: String,
    )

    private val locales by lazy {
        requireNotNull(javaClass.getResourceAsStream("/chat/personas.json")).bufferedReader().use {
            Json.decodeFromString<Map<String, Vocabulary>>(it.readText())
        }
    }

    private val slots = Regex("MobilePersonaName|MobilePersonaDescription")
    // Unicode White_Space as used by Go strings.Fields and supported by Android's regex engine.
    private val whitespace =
        Regex("[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+")

    fun compose(base: String, locale: String, persona: PersonaRef): String {
        val p = persona.personality
        val utility = p.explicitness == Explicitness.Utility
        if (utility && p.replyLength == ReplyLength.Short) return base
        val vocabulary = locales.getValue(locale)
        val selected =
            if (utility) null else vocabulary.voices.getValue(p.explicitness.name.lowercase())
        val style = if (utility) "" else vocabulary.styles.getValue(p.style.name.lowercase())
        val profile = if (utility) "" else profile(vocabulary.profiles, persona)

        return base.split("\n\n").joinToString("\n\n") { section ->
            val heading = section.substringBefore('\n')
            when {
                heading == vocabulary.identityHeading ->
                    joinSections(selected?.identity ?: section, style)
                heading == vocabulary.checkHeading ||
                    section.substringAfter('\n', "").startsWith(vocabulary.checkHeading + "\n") -> {
                    val speechOnly = if (heading == vocabulary.checkHeading) "" else heading + "\n"
                    val detailed =
                        if (p.replyLength == ReplyLength.Detailed) speechOnly + vocabulary.detailed
                        else ""
                    joinSections(
                        profile,
                        selected?.let { speechOnly + it.check } ?: section,
                        detailed,
                    )
                }
                else -> section
            }
        }
    }

    private fun profile(templates: Map<String, String>, persona: PersonaRef): String {
        val name = if (persona.isBuiltIn) "" else normalized(persona.name)
        val description = normalized(persona.personality.description)
        val key =
            when {
                name.isNotEmpty() && description.isNotEmpty() -> "both"
                name.isNotEmpty() -> "name"
                description.isNotEmpty() -> "description"
                else -> return ""
            }
        // Match slots in the immutable template once. A name containing a slot stays literal data.
        return slots.replace(templates.getValue(key)) {
            val text = if (it.value == "MobilePersonaName") name else description
            JsonPrimitive(text).promptJson().removeSurrounding("\"")
        }
    }

    private fun normalized(text: String): String = whitespace.replace(text, " ").trim(' ')

    private fun joinSections(vararg sections: String) =
        sections.filter(String::isNotEmpty).joinToString("\n\n")
}
