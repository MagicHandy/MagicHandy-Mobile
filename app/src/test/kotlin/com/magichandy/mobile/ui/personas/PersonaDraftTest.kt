package com.magichandy.mobile.ui.personas

import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaLimits
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.characterCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The editor's checks agree with the core's counting, and copies get names no persona has. */
internal class PersonaDraftTest {
    private val first = { base: String -> "$base copy" }
    private val numbered = { base: String, number: Int -> "$base copy $number" }

    @Test
    fun `a saved persona's draft is clean, and any change makes it differ`() {
        val kit =
            PersonaRef(
                PersonaId("kit"),
                "Kit",
                summary = "",
                personality = Personality(Explicitness.Explicit),
            )
        val draft = PersonaDraft.of(kit)
        assertEquals(PersonaDraft("Kit", Personality(Explicitness.Explicit)), draft)
        assertTrue(draft.copy(name = "Kat") != draft)
        assertEquals(PersonaDraft(""), PersonaDraft.of(null))
    }

    @Test
    fun `the description is counted per character a person sees`() {
        // Each family emoji is one character on screen but several code units.
        val family = "👨‍👩‍👧"
        val draft = PersonaDraft("Kit", Personality(description = family.repeat(3)))
        assertEquals(3, draft.descriptionLength)
        assertEquals(0, draft.excess)
    }

    @Test
    fun `over the limit, the draft says by how much`() {
        val long = "a".repeat(PersonaLimits.DESCRIPTION + 45)
        assertEquals(45, PersonaDraft("Kit", Personality(description = long)).excess)
        val exact = "a".repeat(PersonaLimits.DESCRIPTION)
        assertEquals(0, PersonaDraft("Kit", Personality(description = exact)).excess)
    }

    @Test
    fun `a blank name doesn't count as a name`() {
        assertFalse(PersonaDraft("   ").hasName)
        assertTrue(PersonaDraft(" Kit ").hasName)
    }

    @Test
    fun `a long name is cut at the limit, keeping whole characters`() {
        val accented = "é" // é as a letter and a combining accent: one character.
        val name = limitName(accented.repeat(PersonaLimits.NAME + 5))
        assertEquals(PersonaLimits.NAME, characterCount(name))
        assertEquals(accented.repeat(PersonaLimits.NAME), name)
        assertEquals("Kit", limitName("Kit"))
    }

    @Test
    fun `a copy's name is free, ignoring case, numbering up from 2`() {
        assertEquals("Kit copy", copyName("Kit", listOf("MagicHandy", "Kit"), first, numbered))
        assertEquals(
            "Kit copy 2",
            copyName("Kit", listOf("MagicHandy", "Kit", "KIT COPY"), first, numbered),
        )
        assertEquals(
            "Kit copy 3",
            copyName("Kit", listOf("Kit", "Kit copy", "kit copy 2"), first, numbered),
        )
    }

    @Test
    fun `a copy of a long name shortens the name, not the word copy`() {
        val long = "N".repeat(PersonaLimits.NAME)
        val copy = copyName(long, listOf(long), first, numbered)
        assertEquals(PersonaLimits.NAME, characterCount(copy))
        assertTrue(copy.endsWith(" copy"))
        val second = copyName(long, listOf(long, copy), first, numbered)
        assertEquals(PersonaLimits.NAME, characterCount(second))
        assertTrue(second.endsWith(" copy 2"))
    }
}
