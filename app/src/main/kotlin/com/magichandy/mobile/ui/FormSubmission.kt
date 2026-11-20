package com.magichandy.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One form's trip to the core. The form keeps what was typed while the core decides, closes only
 * once the core has saved it, and says why when the core refuses (review §6, decision 4).
 */
@Stable
class FormSubmission(
    private val submit: suspend (form: FormIntent) -> FormResult,
    private val scope: CoroutineScope,
) {
    /** True while the core is still deciding; the form's main button waits meanwhile. */
    var isSending: Boolean by mutableStateOf(false)
        private set

    /** Why the core refused the last attempt, until the person edits the form or tries again. */
    var refusal: FormReason? by mutableStateOf(null)
        private set

    /** Sends [form]. [onAccepted] runs once the core has saved it, usually to close the form. */
    fun send(form: FormIntent, onAccepted: () -> Unit) {
        if (isSending) return
        isSending = true
        refusal = null
        scope.launch {
            val result = submit(form)
            isSending = false
            when (result) {
                FormResult.Accepted -> onAccepted()
                is FormResult.Rejected -> refusal = result.reason
            }
        }
    }

    /** Clears a refusal once the person changes something, since it may no longer apply. */
    fun edited() {
        refusal = null
    }
}

/** A [FormSubmission] that lives as long as the form on screen. */
@Composable
fun rememberFormSubmission(submit: suspend (form: FormIntent) -> FormResult): FormSubmission {
    val scope = rememberCoroutineScope()
    return remember(submit, scope) { FormSubmission(submit, scope) }
}

/**
 * Why the core refused [submission]'s last attempt, read out by TalkBack; nothing until then.
 *
 * @param duplicate the words for [FormReason.Duplicate] where the general ones don't fit, such as a
 *   name another device already has.
 */
@Composable
fun FormRefusal(
    submission: FormSubmission,
    modifier: Modifier = Modifier,
    @StringRes duplicate: Int? = null,
) {
    val reason = submission.refusal ?: return
    Text(
        if (reason == FormReason.Duplicate && duplicate != null) stringResource(duplicate)
        else Words.formReason(reason),
        style = MagicHandyTheme.type.small,
        color = MagicHandyTheme.colors.dangerText,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
