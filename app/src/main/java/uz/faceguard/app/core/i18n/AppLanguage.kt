package uz.faceguard.app.core.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
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

/** Where the app should go once startup has resolved the language, session and lock. */
enum class StartupDestination {
    LANGUAGE_SELECTION,

    /** A registered account exists but the parent UI is not unlocked for this process. */
    PIN_UNLOCK,

    HOME,
    WELCOME,
}

/**
 * The single first-launch / startup rule.
 *
 * Language selection comes before anything else: with no persisted language the
 * user is sent to the picker regardless of session state, so a fresh install can
 * never silently fall back to a device language. The choice is deliberately
 * independent of authentication — it is a UI preference, not a credential.
 *
 * [hasRegisteredAccount] (a persisted account id exists) and [isUnlocked] (this
 * process has passed the PIN check) are deliberately separate: a registered
 * account on a later launch is **not** the same as an unlocked UI. "Registered but
 * not unlocked" must land on the PIN screen, so re-opening QALQON never exposes the
 * parental controls, and a restored navigation stack cannot skip the gate either.
 */
fun startupDestination(
    language: AppLanguage?,
    hasRegisteredAccount: Boolean,
    isUnlocked: Boolean,
): StartupDestination = when {
    language == null -> StartupDestination.LANGUAGE_SELECTION
    !hasRegisteredAccount -> StartupDestination.WELCOME
    isUnlocked -> StartupDestination.HOME
    else -> StartupDestination.PIN_UNLOCK
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
     * A context whose resources resolve strings in [language], for consumers that
     * never create a Hilt ViewModel (notification text, the protection overlay).
     *
     * This intentionally returns `createConfigurationContext(...)` — a standalone
     * context with **no Activity** in its chain. That is safe only where nothing
     * calls `hiltViewModel()`; for the Compose tree use [localizedAppContext].
     */
    fun localizedContext(base: Context, language: AppLanguage): Context =
        base.createConfigurationContext(configurationFor(base.resources.configuration, language))

    /**
     * A localized context for the **Compose tree** that keeps the Activity in its
     * context chain.
     *
     * `hiltViewModel()` reads `LocalContext.current` and Hilt's factory requires a
     * `ComponentActivity`, which it finds by unwrapping `ContextWrapper.baseContext`.
     * A bare `createConfigurationContext(...)` result is a `ContextImpl` — not a
     * wrapper — so that unwrap finds no Activity and Hilt throws, which is exactly
     * the crash this replaces.
     *
     * A [ContextThemeWrapper] *around the Activity* fixes that: it is a
     * `ContextWrapper` whose `baseContext` is the real Activity, so Hilt resolves
     * the `ComponentActivity` as before, while `applyOverrideConfiguration(...)`
     * makes its `resources` resolve strings in [language]. The Activity is never
     * replaced, only wrapped.
     *
     * When there is no Activity in the chain (e.g. the protection overlay renders
     * from the application context) it falls back to the plain localized context,
     * which is safe there because that tree creates no Hilt ViewModel.
     */
    fun localizedAppContext(base: Context, language: AppLanguage): Context {
        val activity = base.findComponentActivity()
            ?: return localizedContext(base, language)

        return ContextThemeWrapper(activity, 0).apply {
            applyOverrideConfiguration(
                configurationFor(activity.resources.configuration, language),
            )
        }
    }
}

/**
 * Walks the `ContextWrapper` chain to the [ComponentActivity], mirroring the
 * lookup Hilt itself performs. Returns null when the context is not backed by an
 * Activity (e.g. an application or service context).
 */
fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
