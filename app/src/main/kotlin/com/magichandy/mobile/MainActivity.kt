package com.magichandy.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.magichandy.mobile.platform.ActivityConnection
import com.magichandy.mobile.platform.CoreNotice
import com.magichandy.mobile.ui.MagicHandyRoot

/** The single activity. It only hosts the UI; the core lives in [MagicHandyApp]. */
class MainActivity : ComponentActivity() {
    private val app
        get() = application as MagicHandyApp

    /** True after the ongoing notification was tapped, until the UI has opened Live. */
    private var openLive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.signInTabs.attach(this)
        // Every palette is dark, so the system bars always use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val core = ActivityConnection(this, app, simulatedDevices)
        openLive =
            savedInstanceState?.getBoolean(OPEN_LIVE) ?: (intent.action == CoreNotice.openLive)
        setContent {
            MagicHandyRoot(
                core,
                voice = app.voice,
                openLive = openLive,
                onLiveOpened = { openLive = false },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CoreNotice.openLive) openLive = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(OPEN_LIVE, openLive)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        app.host.visible = true
        app.refreshRadio()
        app.host.access(app.core)
        app.voice.appShown()
    }

    override fun onDestroy() {
        app.signInTabs.detach(this)
        super.onDestroy()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            app.host.visible = false
            app.core.appBackgrounded()
            app.voice.appHidden()
        }
        super.onStop()
    }

    private companion object {
        /** The saved-state key for [openLive]. */
        const val OPEN_LIVE = "open_live"
    }
}
