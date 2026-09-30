package uz.faceguard.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.LanguageState

/**
 * The selected application language — the single source of truth.
 *
 * Deliberately **not** account-scoped, unlike [SettingsStore]: a UI preference
 * must not be tied to authentication, so choosing Russian and then logging out
 * (or registering) keeps Russian, and the first-launch choice is not lost when an
 * account is created.
 *
 * Backed by the existing app preferences DataStore (no Room table, no migration,
 * no second storage mechanism). The key is unscoped on purpose.
 */
@Singleton
class AppLanguageStore @Inject constructor(
    private val store: DataStore<Preferences>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LanguageState>(LanguageState.Loading)
    val state: StateFlow<LanguageState> = _state

    /**
     * The last-read value, for the non-Compose consumers that render user-visible
     * text (notifications, the protection overlay). `null` until the first read
     * completes, which simply means "use the system locale for now".
     */
    @Volatile
    private var cached: AppLanguage? = null

    init {
        scope.launch {
            store.data
                .map { prefs -> AppLanguage.fromTag(prefs[KEY_APP_LANGUAGE]) }
                .collect { language ->
                    cached = language
                    _state.value = language?.let(LanguageState::Selected) ?: LanguageState.NotSelected
                }
        }
    }

    /** Synchronous best-effort read; null while the first read has not completed. */
    fun current(): AppLanguage? = cached

    /** Reads the stored value once, for callers that need a definite answer. */
    suspend fun read(): AppLanguage? = AppLanguage.fromTag(store.data.first()[KEY_APP_LANGUAGE])

    /** Persists the choice; the running UI picks it up through [state]. */
    suspend fun setLanguage(language: AppLanguage) {
        store.edit { it[KEY_APP_LANGUAGE] = language.languageTag }
    }

    private companion object {
        /** Unscoped on purpose: language is an app preference, not an account setting. */
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
    }
}
