package com.magichandy.mobile.ui.personas

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.PersonaLimits
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.api.characterCount
import java.text.BreakIterator

/**
 * What a persona editor holds until Save: the name and personality as the person left them. The
 * core checks them again when they're saved; these checks only shape what the editor offers.
 */
@Immutable
data class PersonaDraft(val name: String, val personality: Personality = Personality()) {
    /** The description's length as the core counts it. */
    val descriptionLength: Int
        get() = characterCount(personality.description)

    /** How many characters the description is over its limit, or 0. */
    val excess: Int
        get() = (descriptionLength - PersonaLimits.DESCRIPTION).coerceAtLeast(0)

    val hasName: Boolean
        get() = name.isNotBlank()

    companion object {
        /** The saved version of [persona], or an empty one for a new persona. */
        fun of(persona: PersonaRef?): PersonaDraft =
            if (persona == null) PersonaDraft("")
            else PersonaDraft(persona.name, persona.personality)

        /** Keeps a draft through rotation and process death. */
        val Saver: Saver<PersonaDraft, Any> =
            listSaver(
                save = { draft ->
                    with(draft.personality) {
                        listOf(
                            draft.name,
                            explicitness.name,
                            style.name,
                            description,
                            replyLength.name,
                        )
                    }
                },
                restore = { saved ->
                    PersonaDraft(
                        saved[0],
                        Personality(
                            explicitness = Explicitness.valueOf(saved[1]),
                            style = ReactionStyle.valueOf(saved[2]),
                            description = saved[3],
                            replyLength = ReplyLength.valueOf(saved[4]),
                        ),
                    )
                },
            )
    }
}

/**
 * [text] cut to at most [PersonaLimits.NAME] characters, counted as the core counts them, so a long
 * paste keeps its start rather than being refused whole.
 */
fun limitName(text: String): String = takeCharacters(text, PersonaLimits.NAME)

/**
 * A name for a copy of [base] that no persona in [taken] has, ignoring case, and that fits the name
 * limit: [first] for the first copy, such as "Kit copy", then [numbered] from 2 up, such as "Kit
 * copy 2". When a copy's name would be too long, [base] is shortened, not the words around it.
 */
fun copyName(
    base: String,
    taken: Collection<String>,
    first: (String) -> String,
    numbered: (String, Int) -> String,
): String {
    var number = 1
    while (true) {
        val candidate = fitName(base) { if (number == 1) first(it) else numbered(it, number) }
        if (taken.none { it.equals(candidate, ignoreCase = true) }) return candidate
        number++
    }
}

private fun fitName(base: String, make: (String) -> String): String {
    var kept = base.trim()
    var name = make(kept)
    while (characterCount(name) > PersonaLimits.NAME && kept.isNotEmpty()) {
        val over = characterCount(name) - PersonaLimits.NAME
        kept = takeCharacters(kept, (characterCount(kept) - over).coerceAtLeast(0)).trimEnd()
        name = make(kept)
    }
    return name
}

/** The first [count] user-perceived characters of [text], as [characterCount] counts them. */
private fun takeCharacters(text: String, count: Int): String {
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(text)
    var end = 0
    repeat(count) {
        val next = boundaries.next()
        if (next == BreakIterator.DONE) return text
        end = next
    }
    return text.substring(0, end)
}
