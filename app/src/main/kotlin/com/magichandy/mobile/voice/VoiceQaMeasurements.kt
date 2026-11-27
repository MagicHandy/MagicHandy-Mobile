package com.magichandy.mobile.voice

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.magichandy.mobile.core.api.VoiceModel

/** Local test-build measurements of the actual hands-free service. Contains no audio or text. */
internal class VoiceQaMeasurements(context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var model: VoiceModel? = null
    private var started = 0L
    private var startBattery: Int? = null
    private var peakThermal = 0

    private val sample =
        object : Runnable {
            override fun run() {
                peakThermal = maxOf(peakThermal, power.currentThermalStatus)
                handler.postDelayed(this, 60_000)
            }
        }

    private fun batteryPercent(): Int? =
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

    fun begin(choice: VoiceModel) {
        finish()
        model = choice
        started = SystemClock.elapsedRealtime()
        startBattery = batteryPercent()
        peakThermal = power.currentThermalStatus
        handler.postDelayed(sample, 60_000)
    }

    fun finish() {
        val choice = model ?: return
        model = null
        handler.removeCallbacks(sample)
        Log.i(
            "MagicHandyVoiceQA",
            "Model=$choice elapsedMs=" +
                (SystemClock.elapsedRealtime() - started) +
                " batteryStartPct=" +
                (startBattery ?: "unknown") +
                " batteryEndPct=" +
                (batteryPercent() ?: "unknown") +
                " peakThermalStatus=" +
                maxOf(peakThermal, power.currentThermalStatus),
        )
    }
}
