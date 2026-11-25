package com.magichandy.mobile.voicelab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magichandy.mobile.R
import com.magichandy.mobile.platform.CoreOwner
import com.magichandy.mobile.ui.LocalizedUi
import com.magichandy.mobile.ui.components.AppFrame
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * The voice test, with its own launcher entry in test builds. It shows the app's Stop bar, which
 * stops every device directly, like every other screen.
 */
class VoiceLabActivity : ComponentActivity() {
    private val lab
        get() = VoiceLab.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val core = (application as CoreOwner).core
        setContent {
            val app by core.state.collectAsStateWithLifecycle()
            val state by lab.state.collectAsStateWithLifecycle()
            var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
            val permission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted ->
                    if (granted) afterPermission?.invoke() else lab.microphoneRefused()
                    afterPermission = null
                }
            val actions = remember {
                fun withMicrophone(action: () -> Unit) {
                    if (hasMicrophone()) {
                        action()
                    } else {
                        afterPermission = action
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                object : LabActions by lab {
                    override fun startListening() = withMicrophone { lab.startListening() }

                    override fun startClip() = withMicrophone { lab.startClip() }

                    override fun share() = shareReport()
                }
            }
            LocalizedUi(app.app.language) {
                MagicHandyTheme(app.app.palette) {
                    AppFrame(
                        stopLabel = stringResource(R.string.stop),
                        onStop = { core.emergencyStop(it) },
                    ) {
                        VoiceLabScreen(state, actions)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lab.onVisible(true)
    }

    override fun onStop() {
        lab.onVisible(false)
        super.onStop()
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun shareReport() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "MagicHandy voice test results")
                .putExtra(Intent.EXTRA_TEXT, lab.report(version))
        startActivity(Intent.createChooser(send, "Share results"))
    }
}
