package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaLimits
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.characterCount
import com.magichandy.mobile.core.ports.PersonaChange
import com.magichandy.mobile.core.ports.PreferenceField
import com.magichandy.mobile.core.ports.SettingsDocument
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.ports.findPersona
import java.util.UUID

internal fun SettingsDocument.savePersona(intent: UserIntent.SavePersona): PersonaChange {
    val id = intent.persona
    val name = intent.name.trim()
    val previous = id?.let { findPersona(it) }
    val builtIn = id == builtInPersona.id
    val reason =
        when {
            id != null && previous == null -> FormReason.NotFound
            name.isBlank() || characterCount(name) > PersonaLimits.NAME -> FormReason.InvalidName
            builtIn && name != builtInPersona.name -> FormReason.InvalidName
            !builtIn && name.equals(builtInPersona.name, true) -> FormReason.InvalidName
            customPersonas.any { it.id != id && it.name.equals(name, true) } -> FormReason.Duplicate
            characterCount(intent.personality.description) > PersonaLimits.DESCRIPTION ->
                FormReason.TooLong
            id == null && customPersonas.size >= PersonaLimits.PERSONAS -> FormReason.LimitReached
            else -> null
        }
    if (reason != null) return PersonaChange.Rejected(reason)
    val savedId = id ?: PersonaId(UUID.randomUUID().toString())
    val fields = buildList {
        if (builtIn && intent.personality != values.builtInPersonality)
            add(PreferenceField.BuiltInPersonality.edit(intent.personality))
        if (intent.use && savedId != values.selectedPersona)
            add(PreferenceField.SelectedPersona.edit(savedId))
    }
    val custom = if (builtIn) null else PersonaRef(savedId, name, "", intent.personality)
    return PersonaChange.Edit(save = custom?.takeUnless { it == previous }, fields = fields)
}

internal fun SettingsDocument.duplicatePersona(intent: UserIntent.DuplicatePersona): PersonaChange {
    val source = findPersona(intent.persona) ?: return PersonaChange.Rejected(FormReason.NotFound)
    return savePersona(UserIntent.SavePersona(null, intent.name, source.personality))
}

internal fun SettingsDocument.deletePersona(id: PersonaId): PersonaChange =
    when {
        id == builtInPersona.id -> PersonaChange.Rejected(FormReason.Unsupported)
        findPersona(id) == null -> PersonaChange.Rejected(FormReason.NotFound)
        else ->
            PersonaChange.Edit(
                delete = id,
                fields =
                    if (values.selectedPersona == id)
                        listOf(PreferenceField.SelectedPersona.edit(builtInPersona.id))
                    else emptyList(),
            )
    }

internal fun SettingsDocument.selectPersona(id: PersonaId): PersonaChange =
    when {
        findPersona(id) == null -> PersonaChange.Rejected(FormReason.NotFound)
        values.selectedPersona == id -> PersonaChange.Edit()
        else -> PersonaChange.Edit(fields = listOf(PreferenceField.SelectedPersona.edit(id)))
    }
