package com.magichandy.mobile.ui

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.magichandy.mobile.core.api.AppLanguage

/**
 * Applies the language chosen in App settings. Android 13 and later keep a per-app language for the
 * whole app, the notification and tile included, and recreate the screen in it. Earlier versions
 * get it inside the UI only, through [LocalizedUi].
 */
fun applyLanguage(context: Context, language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales(language)
    }
}

/**
 * Draws [content] in [language] on Android 12, which has no per-app language. Android 13 and later
 * already resolve resources in the language [applyLanguage] set, so this passes straight through.
 */
@Composable
fun LocalizedUi(language: AppLanguage, content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || language == AppLanguage.System) {
        content()
        return
    }
    val context = LocalContext.current
    val base = LocalConfiguration.current
    val (localized, configuration) =
        remember(context, base, language) {
            val configuration = Configuration(base).apply { setLocales(locales(language)) }
            val resources = context.createConfigurationContext(configuration).resources
            LocalizedContext(context, resources) to configuration
        }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides configuration,
        content = content,
    )
}

/** The activity's context with its resources in another language, so it still starts activities. */
private class LocalizedContext(base: Context, private val localized: Resources) :
    ContextWrapper(base) {
    override fun getResources(): Resources = localized
}

/** The locales for [language]; empty for the phone's own. */
private fun locales(language: AppLanguage): LocaleList =
    when (language) {
        AppLanguage.System -> LocaleList.getEmptyLocaleList()
        AppLanguage.English -> LocaleList.forLanguageTags("en")
        AppLanguage.Spanish -> LocaleList.forLanguageTags("es")
        AppLanguage.PortugueseBrazil -> LocaleList.forLanguageTags("pt-BR")
        AppLanguage.Japanese -> LocaleList.forLanguageTags("ja")
        AppLanguage.ChineseSimplified -> LocaleList.forLanguageTags("zh-Hans")
    }
