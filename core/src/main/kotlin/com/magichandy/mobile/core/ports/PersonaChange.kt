package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef

/** The empty identity owns the original MagicHandy memory on upgraded installations. */
public val builtInPersona: PersonaRef =
    PersonaRef(PersonaId(""), "MagicHandy", "", isBuiltIn = true)

/** One validated change to apply under the store's transaction. */
public sealed interface PersonaChange {
    /** Validation failed before any row, memory or settings field changed. */
    public data class Rejected(public val reason: FormReason) : PersonaChange

    /**
     * [save] upserts one custom row. [delete] removes one row and its memory. Its [fields] are the
     * only preferences that may change in the transaction.
     */
    public data class Edit(
        public val save: PersonaRef? = null,
        public val delete: PersonaId? = null,
        public val fields: List<PreferenceEdit> = emptyList(),
    ) : PersonaChange
}

/** Order visible on every device, independent of the SQLite connection's collation. */
public val personaNameOrder: Comparator<PersonaRef> =
    compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** Merge dirty fields while preserving every disjoint revision and the loaded roster. */
public fun SettingsDocument.withFields(fields: List<PreferenceEdit>): SettingsDocument {
    if (fields.isEmpty()) return this
    val next = revision + 1
    return copy(
        revision = next,
        values = fields.fold(values) { prefs, field -> field.apply(prefs) },
        changedAt = changedAt + fields.associate { it.field to next },
    )
}

/** Project the same roster delta published after the Room transaction commits. */
public fun SettingsDocument.withPersonaEdit(edit: PersonaChange.Edit): SettingsDocument {
    val settings = withFields(edit.fields)
    val saved = edit.save
    return when {
        saved != null ->
            settings.copy(
                customPersonas =
                    (customPersonas.filterNot { it.id == saved.id } + saved).sortedWith(
                        personaNameOrder
                    )
            )
        edit.delete != null ->
            settings.copy(customPersonas = customPersonas.filterNot { it.id == edit.delete })
        else -> settings
    }
}

/** Resolve a stable ID against the same revision as the built-in settings. */
public fun SettingsDocument.findPersona(id: PersonaId): PersonaRef? =
    if (id == builtInPersona.id) builtInPersona.copy(personality = values.builtInPersonality)
    else customPersonas.find { it.id == id }

/** The next speaker, with the built-in as the fallback for a missing reference. */
public val SettingsDocument.activePersona: PersonaRef
    get() =
        findPersona(values.selectedPersona)
            ?: builtInPersona.copy(personality = values.builtInPersonality)
