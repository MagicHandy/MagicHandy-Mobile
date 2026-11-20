package com.magichandy.mobile.ui.preview

import com.magichandy.mobile.core.api.AfterLossBehaviour
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunMembership
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The five saved devices of the v1 boards, one per family. Every value is synthetic. */
object SampleDevices {
    val handy =
        DeviceSummary(
            id = DeviceId("handy-bedroom"),
            name = "Bedroom Handy",
            family = DeviceFamily.Handy,
            connection = ConnectionKind.Bluetooth,
            status = DeviceStatus.Playing,
            membership = RunMembership.InRun(isLead = true),
            battery = Percent(78),
            linkLatency = null,
            output = DeviceOutput.Stroke(Percent(48), Percent(50), Percent(15), Percent(85)),
            settings = DeviceSettings.Handy(model = "The Handy 2", isReversed = false),
            afterLoss = AfterLossBehaviour.PausesWhenBufferRunsOut,
        )

    val vibrator =
        DeviceSummary(
            id = DeviceId("vibrator"),
            name = "Vibrator",
            family = DeviceFamily.Vibrator,
            connection = ConnectionKind.IntifaceOnPhone,
            status = DeviceStatus.Playing,
            membership = RunMembership.InRun(isLead = false),
            battery = null,
            linkLatency = null,
            output = DeviceOutput.Level(Percent(40), Percent(70), followsLead = true),
            settings = DeviceSettings.Vibrator(maxLevel = Percent(70)),
            afterLoss = AfterLossBehaviour.StopsWhenAppDisconnects,
        )

    val coyote =
        DeviceSummary(
            id = DeviceId("coyote"),
            name = "Coyote 3.0",
            family = DeviceFamily.Coyote,
            connection = ConnectionKind.Bluetooth,
            status = DeviceStatus.Connected,
            membership = RunMembership.NextRun,
            battery = null,
            linkLatency = null,
            output = null,
            settings = DeviceSettings.Coyote(maxStrengthA = 40, maxStrengthB = 40),
            afterLoss = AfterLossBehaviour.GoesSilent,
        )

    /** A Coyote that isn't connected, so its limits can change (board V-CoyotePage). */
    val coyoteDisconnected =
        coyote.copy(
            status = DeviceStatus.NotConnected,
            membership = RunMembership.NotInRun,
            settings = DeviceSettings.Coyote(maxStrengthA = 40, maxStrengthB = 25),
        )

    /** A Coyote leading a run, with both channels reported (board V-CoyoteLive). */
    val coyotePlaying =
        coyote.copy(
            status = DeviceStatus.Playing,
            membership = RunMembership.InRun(isLead = true),
            output = DeviceOutput.Channels(strengthA = 12, strengthB = 8),
            settings = DeviceSettings.Coyote(maxStrengthA = 40, maxStrengthB = 25),
        )

    val ultra =
        DeviceSummary(
            id = DeviceId("ultra"),
            name = "Autoblow Ultra",
            family = DeviceFamily.AutoblowUltra,
            connection = ConnectionKind.AutoblowCloud,
            status = DeviceStatus.NotConnected,
            membership = RunMembership.NotInRun,
            battery = null,
            linkLatency = null,
            output = null,
            settings = DeviceSettings.AutoblowUltra,
            afterLoss = AfterLossBehaviour.FinishesLastMove,
        )

    val vacuGlide =
        DeviceSummary(
            id = DeviceId("vacuglide"),
            name = "VacuGlide",
            family = DeviceFamily.VacuGlide,
            connection = ConnectionKind.AutoblowCloud,
            status = DeviceStatus.NeedsSetup,
            membership = RunMembership.NotInRun,
            battery = null,
            linkLatency = 180.milliseconds,
            output = null,
            settings =
                DeviceSettings.VacuGlide(
                    tempoRange = 5..100,
                    mayStepValve = false,
                    checkInEvery = 15.minutes,
                ),
            afterLoss = AfterLossBehaviour.KeepsLastSpeed,
        )
}
