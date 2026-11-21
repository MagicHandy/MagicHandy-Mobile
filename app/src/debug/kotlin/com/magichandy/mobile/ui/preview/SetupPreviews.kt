package com.magichandy.mobile.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.ui.CoreCalls
import com.magichandy.mobile.ui.components.InlineDialog
import com.magichandy.mobile.ui.personas.PersonaDraft
import com.magichandy.mobile.ui.personas.PersonaEditorScreen
import com.magichandy.mobile.ui.personas.PersonaPicker
import com.magichandy.mobile.ui.personas.PersonaPickerSheet
import com.magichandy.mobile.ui.personas.PersonasScreen
import com.magichandy.mobile.ui.setup.ChooseAiExits
import com.magichandy.mobile.ui.setup.ChooseAiScreen
import com.magichandy.mobile.ui.setup.CompanionStepScreen
import com.magichandy.mobile.ui.setup.ReadyExits
import com.magichandy.mobile.ui.setup.ReadyStepScreen

// First-run setup and personas (boards V-SetupModel to V-PersonaStyle).

private val NoReadyExits = ReadyExits({}, {}, {}, {}, {})

@Composable
private fun Editor(persona: PersonaRef) =
    PersonaEditorScreen(
        state = SampleStates.playing,
        persona = persona,
        onIntent = {},
        submit = CoreCalls.None.submit,
        onDone = {},
    )

/** Board V-SetupModel: setup's second step. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SetupChooseAi(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { ChooseAiScreen(ChooseAiExits({}, {}, {}, {}, skip = {})) }

/** Choose your AI from Chat with no model: a page of its own, with Back to Chat and no Skip. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChooseAiFromChat() =
    InFrame(Palette.SteelAzure) { ChooseAiScreen(ChooseAiExits({}, {}, {}, {})) }

/** Board V-SetupCompanion: MagicHandy as it ships. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SetupCompanion(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        CompanionStepScreen(SampleStates.setup, CoreCalls.None.submit, onBack = {}, onNext = {})
    }

/** Board V-SetupReady: a model chosen, no device yet. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SetupReady(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { ReadyStepScreen(SampleStates.setupReady, NoReadyExits) }

/** Board V-SetupReadyNoModel: setup finished without a model. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SetupReadyNoModel(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { ReadyStepScreen(SampleStates.setup, NoReadyExits) }

/** Board V-Personas. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun Personas(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { PersonasScreen(SampleStates.playing, onBack = {}, onNew = {}, onEdit = {}) }

/** Board V-PersonaDefault: MagicHandy, in use. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PersonaDefault(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { Editor(SampleStates.magicHandy) }

/** Board V-PersonaCustom: Kit, saved and not in use. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PersonaCustom(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { Editor(SampleStates.kit) }

/** Kit's editor at twice the text size: Use persona moves under "Your persona". */
@Preview(widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun PersonaCustomLargeText() = InFrame(Palette.SteelAzure) { Editor(SampleStates.kit) }

/** A description over the limit: the counter turns red and says how much to remove. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PersonaOverLimit() =
    InFrame(Palette.SteelAzure) {
        val kit = SampleStates.kit
        Editor(
            kit.copy(
                personality =
                    kit.personality.copy(description = kit.personality.description.repeat(5))
            )
        )
    }

/** Board V-PersonaStyle: the reaction styles over Kit's editor. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PersonaStyle(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        Box(Modifier.fillMaxSize()) {
            Editor(SampleStates.kit)
            PersonaPickerSheet(
                PersonaPicker.Style,
                PersonaDraft.of(SampleStates.kit),
                onDraft = {},
                onClose = {},
            )
        }
    }

/** Deleting Kit asks first, inside the page, so Stop stays in reach. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PersonaDeleteConfirm() =
    InFrame(Palette.SteelAzure) {
        Box(Modifier.fillMaxSize()) {
            val name = SampleStates.kit.name
            Editor(SampleStates.kit)
            InlineDialog(
                title = stringResource(R.string.persona_delete_title, name),
                body = stringResource(R.string.persona_delete_body, name),
                dismiss = stringResource(R.string.action_cancel),
                onDismiss = {},
                confirm = stringResource(R.string.persona_delete_confirm, name),
                onConfirm = {},
                isDestructive = true,
            )
        }
    }
