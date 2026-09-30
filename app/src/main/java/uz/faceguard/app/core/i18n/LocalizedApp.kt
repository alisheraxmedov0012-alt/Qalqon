package uz.faceguard.app.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Applies the selected application language to the whole Compose tree.
 *
 * It provides a locale-aware [android.content.Context] and the matching
 * [android.content.res.Configuration] as the composition locals that
 * `stringResource(...)` and `LocalContext.current.getString(...)` actually read.
 * Changing [language] therefore re-renders every user-facing string immediately,
 * with **no activity restart**, and works on every supported API level (this is
 * plain Android resource resolution, not a platform-version-specific API).
 *
 * When [language] is null (nothing selected yet, or still loading) the system
 * locale is left untouched, so a fresh install shows the picker in the device
 * language until the user chooses.
 *
 * This is a *consumer* of the single persisted value, not a second language
 * system: `AppLanguageStore` remains the one source of truth.
 */
@Composable
fun LocalizedApp(
    language: AppLanguage?,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current

    if (language == null) {
        content()
        return
    }

    val localized = remember(language, baseContext, baseConfiguration) {
        AppLocale.localizedContext(baseContext, language) to
            AppLocale.configurationFor(baseConfiguration, language)
    }

    CompositionLocalProvider(
        LocalContext provides localized.first,
        LocalConfiguration provides localized.second,
        content = content,
    )
}
