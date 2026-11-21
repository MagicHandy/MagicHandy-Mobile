package com.magichandy.mobile.ui.personas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.FormSubmission
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.ButtonRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.components.InlineDialog
import com.magichandy.mobile.ui.components.InlineMenu
import com.magichandy.mobile.ui.components.MenuAction
import com.magichandy.mobile.ui.components.PushDown
import com.magichandy.mobile.ui.components.bottomHairline
import com.magichandy.mobile.ui.format.PersonaWords
import com.magichandy.mobile.ui.models.Page
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** What the editor shows over the page besides a picker. */
private enum class EditorOverlay {
    Menu,
    Delete,
    Discard,

    /** Unsaved changes stand in the way of duplicating the saved persona. */
    DiscardThenDuplicate,
}

/**
 * One persona's editor (boards V-PersonaDefault and V-PersonaCustom), or a new persona's when
 * [persona] is null. Changes stay in a draft until Save, which closes the editor. Leaving with
 * unsaved changes asks first. MagicHandy keeps its name and can go back to its defaults; a persona
 * the person made can be renamed in its title, duplicated or deleted.
 *
 * @param onDone leaves the editor once its work is saved or dropped.
 */
@Composable
fun PersonaEditorScreen(
    state: AppState,
    persona: PersonaRef?,
    onIntent: (UserIntent) -> Unit,
    submit: suspend (form: FormIntent) -> FormResult,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val saved = PersonaDraft.of(persona)
    var draft by rememberSaveable(stateSaver = PersonaDraft.Saver) { mutableStateOf(saved) }
    var picker by rememberSaveable { mutableStateOf<PersonaPicker?>(null) }
    var overlay by rememberSaveable { mutableStateOf<EditorOverlay?>(null) }
    var wasReset by rememberSaveable { mutableStateOf(false) }
    var isNameMissing by rememberSaveable { mutableStateOf(false) }
    val submission = rememberFormSubmission(submit)
    val nameFocus = remember { FocusRequester() }
    val isBuiltIn = persona?.isBuiltIn == true
    val isInUse = persona != null && persona.id == settings.persona.id
    // A new persona has nothing saved to use, so its first save can also start using it.
    val isDirty = persona == null || draft != saved
    val hasEdits = draft != saved
    val update: (PersonaDraft) -> Unit = {
        draft = it
        wasReset = false
        submission.edited()
    }
    val save = { use: Boolean ->
        if (!draft.hasName) {
            isNameMissing = true
            nameFocus.requestFocus()
        } else {
            submission.send(
                UserIntent.SavePersona(persona?.id, draft.name.trim(), draft.personality, use),
                onDone,
            )
        }
    }
    val copyName = rememberCopyName(persona, settings.personas)
    val duplicate = {
        if (persona != null && copyName != null) {
            submission.send(UserIntent.DuplicatePersona(persona.id, copyName), onDone)
        }
    }
    val leave = { if (hasEdits) overlay = EditorOverlay.Discard else onDone() }
    BackHandler(enabled = hasEdits && overlay == null && picker == null) {
        overlay = EditorOverlay.Discard
    }
    LaunchedEffect(Unit) { if (persona == null) nameFocus.requestFocus() }

    Box(modifier.fillMaxSize()) {
        Page(Modifier) {
            EditorTop(persona, onBack = leave, onMore = { overlay = EditorOverlay.Menu })
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.m),
            ) {
                PersonaTile(persona, draft.name)
                if (isBuiltIn) {
                    Text(
                        draft.name,
                        style = MagicHandyTheme.type.pageTitle,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                } else {
                    NameField(
                        name = draft.name,
                        onChange = {
                            isNameMissing = false
                            update(draft.copy(name = it))
                        },
                        focus = nameFocus,
                        isMissing = isNameMissing && !draft.hasName,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (isNameMissing && !draft.hasName) {
                Text(
                    stringResource(R.string.persona_name_missing),
                    style = MagicHandyTheme.type.small,
                    color = MagicHandyTheme.colors.dangerText,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            UseRow(
                isBuiltIn = isBuiltIn,
                isInUse = isInUse,
                isDirty = isDirty,
                isEnabled = draft.excess == 0 && !submission.isSending,
                onUse = {
                    if (persona != null && !isDirty) onIntent(UserIntent.SetPersona(persona.id))
                    else save(true)
                },
            )
            Choices(draft, update, onPick = { picker = it })
            Hint(
                stringResource(
                    if (wasReset) R.string.persona_reset_done else R.string.persona_wording_only
                )
            )
            PushDown()
            FormRefusal(submission, duplicate = R.string.form_duplicate_persona_name)
            SaveButtons(
                isBuiltIn = isBuiltIn,
                canSave = isDirty && draft.excess == 0,
                submission = submission,
                canReset = draft.personality != Personality(),
                onReset = {
                    draft = draft.copy(personality = Personality())
                    wasReset = true
                    submission.edited()
                },
                onSave = { save(false) },
            )
        }
        picker?.let { PersonaPickerSheet(it, draft, update, onClose = { picker = null }) }
        if (persona != null) {
            EditorOverlays(
                overlay = overlay,
                persona = persona,
                isInUse = isInUse,
                builtInName =
                    settings.personas.firstOrNull { it.isBuiltIn }?.name
                        ?: stringResource(R.string.app_name),
                submit = submit,
                onOverlay = { overlay = it },
                onDuplicate = {
                    if (hasEdits) {
                        overlay = EditorOverlay.DiscardThenDuplicate
                    } else {
                        overlay = null
                        duplicate()
                    }
                },
                onDiscard = { thenDuplicate ->
                    overlay = null
                    draft = saved
                    if (thenDuplicate) duplicate() else onDone()
                },
            )
        } else if (overlay == EditorOverlay.Discard) {
            DiscardDialog(onKeep = { overlay = null }, onDiscard = onDone)
        }
    }
}

/** The name a copy of [persona] gets: "Kit copy", or the next number free. */
@Composable
private fun rememberCopyName(persona: PersonaRef?, personas: List<PersonaRef>): String? {
    val first = stringResource(R.string.persona_copy_name)
    val numbered = stringResource(R.string.persona_copy_name_numbered)
    return remember(persona, personas, first, numbered) {
        persona?.let { original ->
            copyName(
                original.name,
                personas.map { it.name },
                first = { first.format(it) },
                numbered = { base, number -> numbered.format(base, number) },
            )
        }
    }
}

/** Back to Personas, and More options for a persona the person made. */
@Composable
private fun EditorTop(persona: PersonaRef?, onBack: () -> Unit, onMore: () -> Unit) {
    val personas = stringResource(R.string.personas_title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = MagicHandyTheme.dimens.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            AppIcons.Back,
            stringResource(R.string.back_to_page, personas),
            onBack,
            Modifier.offset(x = -MagicHandyTheme.dimens.backOffset),
        )
        Text(
            personas,
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.muted,
            modifier = Modifier.weight(1f),
        )
        if (persona != null && !persona.isBuiltIn) {
            IconAction(
                AppIcons.More,
                stringResource(R.string.persona_more, persona.name),
                onMore,
                Modifier.offset(x = MagicHandyTheme.dimens.backOffset),
            )
        }
    }
}

/** The persona's name as its title, edited in place, with a pencil to show it can be. */
@Composable
private fun NameField(
    name: String,
    onChange: (String) -> Unit,
    focus: FocusRequester,
    isMissing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val label = stringResource(R.string.persona_name)
    BasicTextField(
        value = name,
        onValueChange = { onChange(limitName(it)) },
        singleLine = true,
        textStyle = MagicHandyTheme.type.pageTitle.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
        modifier = modifier.focusRequester(focus).semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(
                Modifier.heightIn(min = dimens.touch)
                    .bottomHairline(if (isMissing) colors.danger else colors.fieldLine)
                    .padding(bottom = dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s),
            ) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) {
                        Text(
                            stringResource(R.string.persona_name_hint),
                            style = MagicHandyTheme.type.pageTitle,
                            color = colors.faint,
                        )
                    }
                    inner()
                }
                Icon(
                    AppIcons.Pencil,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(dimens.smallIcon),
                )
            }
        },
    )
}

/**
 * Whose persona this is and whether it's in use. One not in use offers Use persona, or Save and use
 * while it has unsaved changes. With large text the button moves under the words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UseRow(
    isBuiltIn: Boolean,
    isInUse: Boolean,
    isDirty: Boolean,
    isEnabled: Boolean,
    onUse: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.m),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(
                when {
                    isBuiltIn && isInUse -> R.string.persona_built_in_in_use
                    isBuiltIn -> R.string.persona_built_in
                    isInUse -> R.string.persona_yours_in_use
                    else -> R.string.persona_yours
                }
            ),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.muted,
            modifier = Modifier.weight(1f),
        )
        if (!isInUse) {
            AppButton(
                stringResource(
                    if (isDirty) R.string.persona_save_and_use else R.string.persona_use
                ),
                onUse,
                isEnabled = isEnabled,
            )
        }
    }
}

/** The level, style, description and reply length, as the boards order them. */
@Composable
private fun Choices(
    draft: PersonaDraft,
    onDraft: (PersonaDraft) -> Unit,
    onPick: (PersonaPicker) -> Unit,
) {
    val personality = draft.personality
    PickerCard(
        label = stringResource(R.string.how_explicit),
        value = PersonaWords.level(personality.explicitness),
        detail = PersonaWords.levelDefinition(personality.explicitness),
        onClick = { onPick(PersonaPicker.Level) },
    )
    PickerCard(
        label = stringResource(R.string.reaction_style),
        value = PersonaWords.style(personality.style),
        detail = PersonaWords.styleDefinition(personality.style),
        onClick = { onPick(PersonaPicker.Style) },
    )
    DescriptionCard(
        draft,
        { onDraft(draft.copy(personality = personality.copy(description = it))) },
    )
    ReplyLengthCard(
        personality.replyLength,
        { onDraft(draft.copy(personality = personality.copy(replyLength = it))) },
    )
}

/** Save, and for MagicHandy, Reset to default beside it. */
@Composable
private fun SaveButtons(
    isBuiltIn: Boolean,
    canSave: Boolean,
    submission: FormSubmission,
    canReset: Boolean,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    val save: @Composable () -> Unit = {
        AppButton(
            stringResource(
                if (submission.isSending) R.string.form_saving else R.string.action_save
            ),
            onSave,
            kind = ButtonKind.Primary,
            isEnabled = canSave && !submission.isSending,
            modifier = if (isBuiltIn) Modifier else Modifier.fillMaxWidth(),
        )
    }
    if (isBuiltIn) {
        ButtonRow(weights = listOf(1f, RESET_SAVE_RATIO)) {
            AppButton(stringResource(R.string.persona_reset), onReset, isEnabled = canReset)
            save()
        }
    } else {
        save()
    }
}

/** The More options menu and the confirmations, for a persona that's saved. */
@Composable
private fun BoxScope.EditorOverlays(
    overlay: EditorOverlay?,
    persona: PersonaRef,
    isInUse: Boolean,
    builtInName: String,
    submit: suspend (form: FormIntent) -> FormResult,
    onOverlay: (EditorOverlay?) -> Unit,
    onDuplicate: () -> Unit,
    onDiscard: (thenDuplicate: Boolean) -> Unit,
) {
    when (overlay) {
        EditorOverlay.Menu ->
            InlineMenu(
                title = stringResource(R.string.persona_more, persona.name),
                onDismiss = { onOverlay(null) },
            ) {
                MenuAction(stringResource(R.string.persona_duplicate), onDuplicate)
                MenuAction(
                    stringResource(R.string.persona_delete),
                    { onOverlay(EditorOverlay.Delete) },
                    color = MagicHandyTheme.colors.dangerText,
                )
            }
        EditorOverlay.Delete -> {
            // Deleting closes the editor: once the core drops the persona, the root leaves.
            val deletion = rememberFormSubmission(submit)
            InlineDialog(
                title = stringResource(R.string.persona_delete_title, persona.name),
                body =
                    if (isInUse) {
                        stringResource(
                            R.string.persona_delete_body_in_use,
                            persona.name,
                            builtInName,
                        )
                    } else {
                        stringResource(R.string.persona_delete_body, persona.name)
                    },
                dismiss = stringResource(R.string.action_cancel),
                onDismiss = { onOverlay(null) },
                confirm = stringResource(R.string.persona_delete_confirm, persona.name),
                onConfirm = { deletion.send(UserIntent.DeletePersona(persona.id)) {} },
                isDestructive = true,
                isConfirmEnabled = !deletion.isSending,
                extra = { FormRefusal(deletion) },
            )
        }
        EditorOverlay.Discard -> DiscardDialog({ onOverlay(null) }, { onDiscard(false) })
        EditorOverlay.DiscardThenDuplicate ->
            DiscardDialog({ onOverlay(null) }, { onDiscard(true) })
        null -> Unit
    }
}

@Composable
private fun BoxScope.DiscardDialog(
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    InlineDialog(
        title = stringResource(R.string.persona_discard_title),
        body = stringResource(R.string.persona_discard_body),
        dismiss = stringResource(R.string.persona_keep_editing),
        onDismiss = onKeep,
        confirm = stringResource(R.string.persona_discard),
        onConfirm = onDiscard,
        isDestructive = true,
    )
}

/** Save is the main action, so it takes more of the row than Reset to default. */
private const val RESET_SAVE_RATIO = 1.4f
