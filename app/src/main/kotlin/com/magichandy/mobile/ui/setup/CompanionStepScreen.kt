package com.magichandy.mobile.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.ButtonRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PushDown
import com.magichandy.mobile.ui.format.PersonaWords
import com.magichandy.mobile.ui.personas.LevelChoices
import com.magichandy.mobile.ui.personas.PersonaDraft
import com.magichandy.mobile.ui.personas.PersonaPicker
import com.magichandy.mobile.ui.personas.PersonaPickerSheet
import com.magichandy.mobile.ui.personas.PickerCard
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Holds the unsaved companion draft on setup's third step. Skipping resets the draft to the saved
 * personality so returning with Back never restores discarded edits.
 */
@Stable
internal class CompanionStepDraft(saved: PersonaDraft) {
    var draft: PersonaDraft by mutableStateOf(saved)
        private set

    var lastSaved: PersonaDraft by mutableStateOf(saved)
        private set

    fun syncSaved(saved: PersonaDraft) {
        if (saved != lastSaved) {
            if (draft == lastSaved) draft = saved
            lastSaved = saved
        }
    }

    fun edit(updated: PersonaDraft) {
        draft = updated
    }

    fun discard(saved: PersonaDraft) {
        draft = saved
        lastSaved = saved
    }

    companion object {
        val Saver: Saver<CompanionStepDraft, Any> =
            listSaver(
                save = { state ->
                    listOf(
                        state.draft.name,
                        state.draft.personality.explicitness.name,
                        state.draft.personality.style.name,
                        state.draft.personality.description,
                        state.draft.personality.replyLength.name,
                        state.lastSaved.name,
                        state.lastSaved.personality.explicitness.name,
                        state.lastSaved.personality.style.name,
                        state.lastSaved.personality.description,
                        state.lastSaved.personality.replyLength.name,
                    )
                },
                restore = { saved ->
                    fun draftAt(offset: Int) =
                        PersonaDraft(
                            saved[offset],
                            Personality(
                                explicitness = Explicitness.valueOf(saved[offset + 1]),
                                style = ReactionStyle.valueOf(saved[offset + 2]),
                                description = saved[offset + 3],
                                replyLength = ReplyLength.valueOf(saved[offset + 4]),
                            ),
                        )
                    CompanionStepDraft(draftAt(5)).apply { edit(draftAt(0)) }
                },
            )
    }
}

/**
 * Setup's Your companion (board V-SetupCompanion): how explicit MagicHandy may be, its reaction
 * style and its description. Continue saves them; Skip for now leaves MagicHandy as it is.
 */
@Composable
fun CompanionStepScreen(
    state: AppState,
    submit: suspend (form: FormIntent) -> FormResult,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val persona = settings.personas.firstOrNull { it.isBuiltIn } ?: settings.persona
    val saved = PersonaDraft.of(persona)
    val stepDraft = rememberSaveable(saver = CompanionStepDraft.Saver) { CompanionStepDraft(saved) }
    stepDraft.syncSaved(saved)
    val draft = stepDraft.draft
    var picker by rememberSaveable { mutableStateOf<PersonaPicker?>(null) }
    val submission = rememberFormSubmission(submit)
    val update: (PersonaDraft) -> Unit = {
        stepDraft.edit(it)
        submission.edited()
    }
    val personality = draft.personality
    Box(modifier.fillMaxSize()) {
        SetupPage(
            step = 3,
            title = stringResource(R.string.companion_title),
            lead = stringResource(R.string.companion_persona, persona.name),
            onBack = onBack,
            backTo = stringResource(R.string.choose_ai_title),
        ) {
            Text(
                stringResource(R.string.companion_explicit),
                style = MagicHandyTheme.type.bodyStrong,
            )
            LevelChoices(
                selected = personality.explicitness,
                onSelect = {
                    update(draft.copy(personality = personality.copy(explicitness = it)))
                },
            )
            Hint(stringResource(R.string.companion_same))
            PickerCard(
                label = stringResource(R.string.reaction_style),
                value = PersonaWords.style(personality.style),
                detail = PersonaWords.styleDefinition(personality.style),
                onClick = { picker = PersonaPicker.Style },
            )
            // Once written, the description itself shows here, so it's clear something was added.
            PickerCard(
                label = stringResource(R.string.persona_description),
                value = null,
                detail =
                    personality.description.ifBlank {
                        stringResource(R.string.persona_description_used)
                    },
                onClick = { picker = PersonaPicker.Description },
                detailLines = 2,
            )
            PushDown()
            FormRefusal(submission)
            ButtonRow(weights = listOf(1f, 1f)) {
                AppButton(
                    stringResource(R.string.setup_skip),
                    {
                        stepDraft.discard(saved)
                        picker = null
                        submission.edited()
                        onNext()
                    },
                    isEnabled = !submission.isSending,
                )
                AppButton(
                    stringResource(
                        if (submission.isSending) R.string.form_saving else R.string.setup_continue
                    ),
                    {
                        if (draft == saved) {
                            onNext()
                        } else {
                            submission.send(
                                UserIntent.SavePersona(persona.id, persona.name, personality),
                                onNext,
                            )
                        }
                    },
                    kind = ButtonKind.Primary,
                    isEnabled = draft.excess == 0 && !submission.isSending,
                )
            }
        }
        picker?.let { PersonaPickerSheet(it, draft, update, onClose = { picker = null }) }
    }
}
