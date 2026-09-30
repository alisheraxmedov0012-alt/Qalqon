package uz.faceguard.app.core.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale
import uz.faceguard.app.R

/**
 * The languages QALQON ships localized resources for. Mirrors the resource
 * qualifier folders: `values` (Uzbek, the default), `values-en`, `values-ru`.
 *
 * [languageTag] is the single persisted identifier and the value handed to the
 * Android locale APIs, so there is exactly one source of truth for "which
 * language is QALQON using".
 */
enum class AppLanguage(
    val languageTag: String,
    /** The language's own (endonym) name, so the picker is readable in any locale. */
    @StringRes val nativeNameRes: Int,
) {
    UZBEK("uz", R.string.language_name_uzbek),
    ENGLISH("en", R.string.language_name_english),
    RUSSIAN("ru", R.string.language_name_russian);

    companion object {
        /** Parses a persisted/possibly-foreign tag; unknown values are not a language. */
        fun fromTag(tag: String?): AppLanguage? =
            tag?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                entries.firstOrNull { it.languageTag.equals(raw, ignoreCase = true) }
            }
    }
}

/** Whether a language has been chosen yet. Distinguishes "not read" from "not chosen". */
sealed interface LanguageState {
    /** The persisted value has not been read yet; nothing is applied. */
    data object Loading : LanguageState

    /** No language has ever been selected — the first-launch picker must be shown. */
    data object NotSelected : LanguageState

    data class Selected(val language: AppLanguage) : LanguageState {
        val tag: String get() = language.languageTag
    }
}

/** Where the app should go once startup has resolved the language and session. */
enum class StartupDestination { LANGUAGE_SELECTION, HOME, WELCOME }

/**
 * The single first-launch rule.
 *
 * Language selection comes before anything else: with no persisted language the
 * user is sent to the picker regardless of session state, so a fresh install can
 * never silently fall back to a device language. The choice is deliberately
 * independent of authentication — it is a UI preference, not a credential.
 */
fun startupDestination(language: AppLanguage?, hasSession: Boolean): StartupDestination = when {
    language == null -> StartupDestination.LANGUAGE_SELECTION
    hasSession -> StartupDestination.HOME
    else -> StartupDestination.WELCOME
}

/**
 * Pure locale plumbing: turns an [AppLanguage] into the Android locale,
 * [Configuration] and locale-aware [Context] used to render the UI.
 *
 * Kept separate from Compose so the mapping is unit-testable on the JVM.
 */
object AppLocale {

    fun localeFor(language: AppLanguage): Locale = Locale.forLanguageTag(language.languageTag)

    /** A copy of [base] with the locale set to [language]; the rest is preserved. */
    fun configurationFor(base: Configuration, language: AppLanguage): Configuration =
        Configuration(base).apply { setLocale(localeFor(language)) }

    /**
     * A context whose resources resolve strings in [language]. Used both for the
     * Compose tree (immediate switch, no restart) and for the non-Compose
     * consumers that render user-visible text (notifications, overlay).
     */
    fun localizedContext(base: Context, language: AppLanguage): Context =
        base.createConfigurationContext(configurationFor(base.resources.configuration, language))
}
