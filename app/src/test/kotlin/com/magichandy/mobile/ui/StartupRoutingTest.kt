package com.magichandy.mobile.ui

import com.magichandy.mobile.core.api.StartupState
import com.magichandy.mobile.ui.preview.SampleStates
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

internal class StartupRoutingTest {
    @Test
    fun `an interrupted run takes priority over database failure and onboarding`() {
        val state = SampleStates.interrupted
        val unknownConsent = state.firstRun.copy(hasAcknowledgedAdultsOnly = false)
        for (phase in StartupState.entries) {
            assertEquals(
                InitialScreen.Recovery,
                initialScreen(state.copy(startup = phase, firstRun = unknownConsent)),
            )
        }
        val ordinary = state.copy(interruptedRun = null, firstRun = unknownConsent)
        assertEquals(
            InitialScreen.Loading,
            initialScreen(ordinary.copy(startup = StartupState.Loading)),
        )
        assertEquals(
            InitialScreen.StorageError,
            initialScreen(ordinary.copy(startup = StartupState.StorageUnavailable)),
        )
        assertEquals(
            InitialScreen.Consent,
            initialScreen(ordinary.copy(startup = StartupState.Ready)),
        )
    }
}
