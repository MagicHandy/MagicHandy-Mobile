package com.magichandy.mobile.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DiagnosticsReport
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shares the redacted diagnostics report from App settings. The report is built only when the
 * person asks, written to the app's cache (the only folder its FileProvider exposes) and handed to
 * the Android share sheet as a text file.
 */
@Stable
class DiagnosticsShare(
    private val context: Context,
    private val report: suspend () -> DiagnosticsReport,
    private val chooserTitle: String,
    private val scope: CoroutineScope,
) {
    /** True while the report is being built and written. */
    var isPreparing: Boolean by mutableStateOf(false)
        private set

    /** True when the last report couldn't be written. */
    var hasFailed: Boolean by mutableStateOf(false)
        private set

    /** Builds the report and opens the share sheet. */
    fun share() {
        if (isPreparing) return
        isPreparing = true
        hasFailed = false
        scope.launch {
            val uri = write(report())
            isPreparing = false
            if (uri == null) {
                hasFailed = true
                return@launch
            }
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            context.startActivity(
                Intent.createChooser(send, chooserTitle)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
            )
        }
    }

    /**
     * Keeps only the newest report, under the name the core chose. Null when it can't be written.
     */
    private suspend fun write(report: DiagnosticsReport): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val folder = File(context.cacheDir, DIAGNOSTICS_FOLDER).apply { mkdirs() }
                folder.listFiles()?.forEach { it.delete() }
                val file = File(folder, File(report.fileName).name)
                file.writeText(report.text)
                FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private companion object {
        const val DIAGNOSTICS_FOLDER = "diagnostics"
        const val AUTHORITY_SUFFIX = ".diagnostics"
    }
}

/** A [DiagnosticsShare] for the screen showing it. */
@Composable
fun rememberDiagnosticsShare(report: suspend () -> DiagnosticsReport): DiagnosticsShare {
    val context = LocalContext.current
    val title = stringResource(R.string.app_diagnostics)
    val scope = rememberCoroutineScope()
    return remember(context, report, title, scope) {
        DiagnosticsShare(context, report, title, scope)
    }
}
