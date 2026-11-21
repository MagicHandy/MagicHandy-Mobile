package com.magichandy.mobile.ui.setup

import androidx.compose.runtime.saveable.SaverScope
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.addDeviceOrigin
import com.magichandy.mobile.ui.devices.AddDeviceOrigin
import com.magichandy.mobile.ui.devices.addDeviceReturnResources
import com.magichandy.mobile.ui.models.ProviderOrigin
import com.magichandy.mobile.ui.models.chatGptSignInIntent
import com.magichandy.mobile.ui.models.openRouterSaveIntent
import com.magichandy.mobile.ui.models.serverCheckForAddress
import com.magichandy.mobile.ui.models.serverSaveIntent
import com.magichandy.mobile.ui.navigation.Route
import com.magichandy.mobile.ui.personas.PersonaDraft
import com.magichandy.mobile.ui.providerOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SetupFlowTest {
    @Test
    fun `editing the LAN address hides readiness failure and in-flight results from the previous address`() {
        val address = "fixture-a:11434"
        val found =
            ServerCheck.Found(
                address,
                ProviderKind.Ollama,
                false,
                listOf("model"),
                "model",
                CapabilityLevel.Simple,
                null,
                16384,
                null,
                null,
            )
        for (result in
            listOf(
                found,
                ServerCheck.Checking(address),
                ServerCheck.Failed(address, ServerCheckFailure.ProviderRefused),
            )) {
            assertEquals(ServerCheck.Idle, serverCheckForAddress(result, "fixture-b:11434"))
            assertEquals(result, serverCheckForAddress(result, " $address "))
        }
    }

    private fun roundTrip(state: CompanionStepDraft): CompanionStepDraft {
        val scope = SaverScope { true }
        val saved = requireNotNull(with(CompanionStepDraft.Saver) { scope.save(state) })
        return requireNotNull(CompanionStepDraft.Saver.restore(saved))
    }

    @Test
    fun `Setup and Chat Choose AI build chat-selecting intents while Models keeps ordinary saves`() {
        assertEquals(ProviderOrigin.ChooseAi, providerOrigin(Route.Setup(2)))
        assertEquals(ProviderOrigin.ChooseAi, providerOrigin(Route.ChooseAi))
        assertEquals(ProviderOrigin.Models, providerOrigin(Route.Models))
        assertEquals(ProviderOrigin.Models, providerOrigin(null))

        assertEquals(
            UserIntent.StartChatGptSignInForChat,
            chatGptSignInIntent(ProviderOrigin.ChooseAi),
        )
        assertEquals(UserIntent.StartChatGptSignIn, chatGptSignInIntent(ProviderOrigin.Models))

        val chooseAiRouter =
            openRouterSaveIntent(
                key = "synthetic-key",
                model = "fixture/router",
                capability = CapabilityLevel.Advanced,
                isReasoningOn = false,
                origin = ProviderOrigin.ChooseAi,
            )
        val modelsRouter =
            openRouterSaveIntent(
                key = "synthetic-key",
                model = "fixture/router",
                capability = CapabilityLevel.Advanced,
                isReasoningOn = false,
                origin = ProviderOrigin.Models,
            )
        assertTrue(chooseAiRouter.selectForChat)
        assertFalse(modelsRouter.selectForChat)

        val chooseAiServer =
            serverSaveIntent(
                name = "Ollama at 127.0.0.1:11434",
                address = "127.0.0.1:11434",
                model = "fixture:12b",
                capability = CapabilityLevel.Intermediate,
                origin = ProviderOrigin.ChooseAi,
            )
        val modelsServer =
            serverSaveIntent(
                name = "Ollama at 127.0.0.1:11434",
                address = "127.0.0.1:11434",
                model = "fixture:12b",
                capability = CapabilityLevel.Intermediate,
                origin = ProviderOrigin.Models,
            )
        assertTrue(chooseAiServer.selectForChat)
        assertFalse(modelsServer.selectForChat)
    }

    @Test
    fun `skipping companion edits restores the saved personality when returning with Back`() {
        val defaultSaved = PersonaDraft("MagicHandy", Personality())
        val step = CompanionStepDraft(defaultSaved)
        val unsaved =
            PersonaDraft(
                "MagicHandy",
                Personality(
                    explicitness = Explicitness.Explicit,
                    style = ReactionStyle.Dominant,
                    description = "Unsaved synthetic edit",
                    replyLength = ReplyLength.Detailed,
                ),
            )
        step.edit(unsaved)
        assertEquals(unsaved, roundTrip(step).draft)

        step.discard(defaultSaved)
        val afterBack = roundTrip(step)
        assertEquals(defaultSaved, afterBack.draft)

        val customSaved =
            PersonaDraft(
                "MagicHandy",
                Personality(
                    explicitness = Explicitness.Warm,
                    style = ReactionStyle.Playful,
                    description = "Saved synthetic personality",
                ),
            )
        afterBack.syncSaved(customSaved)
        assertEquals(customSaved, afterBack.draft)
        afterBack.edit(unsaved)
        afterBack.discard(customSaved)
        assertEquals(customSaved, roundTrip(afterBack).draft)
    }

    @Test
    fun `Add device tracks whether it was opened from Setup or Devices`() {
        assertEquals(AddDeviceOrigin.Setup, addDeviceOrigin(Route.Setup(4)))
        assertEquals(AddDeviceOrigin.Devices, addDeviceOrigin(Route.Devices))
        assertEquals(AddDeviceOrigin.Devices, addDeviceOrigin(null))

        val fromSetup = addDeviceReturnResources(AddDeviceOrigin.Setup)
        assertEquals(R.string.setup_add_breadcrumb, fromSetup.breadcrumb)
        assertEquals(R.string.setup_label, fromSetup.backTo)

        val fromDevices = addDeviceReturnResources(AddDeviceOrigin.Devices)
        assertEquals(R.string.add_breadcrumb, fromDevices.breadcrumb)
        assertNull(fromDevices.backTo)
    }
}
