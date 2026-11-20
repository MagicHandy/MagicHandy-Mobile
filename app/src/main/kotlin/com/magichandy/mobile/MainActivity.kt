package com.magichandy.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.magichandy.mobile.ui.MagicHandyRoot

/** The single activity. It only hosts the UI; the core lives in [MagicHandyApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every palette is dark, so the system bars always use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val core = (application as MagicHandyApp).core
        setContent { MagicHandyRoot(core) }
    }
}
