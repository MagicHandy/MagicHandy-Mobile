package com.magichandy.mobile.ui.stop

import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopReceipt

/**
 * Remembers which receipt the person dismissed, as a string that survives configuration changes.
 * The core keeps a receipt while any device is unconfirmed, so Done only hides it here.
 */
internal fun dismissalKey(receipt: StopReceipt): String =
    (listOf(receipt.pressCount.toString()) + unconfirmedDevices(receipt)).joinToString(SEPARATOR)

/**
 * Whether [receipt] stays hidden after the dismissal saved as [key]. It shows again when Stop is
 * pressed again or another device turns out unconfirmed.
 */
internal fun isDismissed(receipt: StopReceipt, key: String): Boolean {
    val parts = key.split(SEPARATOR)
    if (parts.first() != receipt.pressCount.toString()) return false
    val seen = parts.drop(1).toSet()
    return unconfirmedDevices(receipt).all { it in seen }
}

private fun unconfirmedDevices(receipt: StopReceipt): List<String> =
    receipt.outcomes.filter { it.outcome == StopOutcome.Unconfirmed }.map { it.device.value }

private const val SEPARATOR = "\n"
