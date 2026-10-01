package uz.faceguard.app.core.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the **parent control UI** has been unlocked for this process.
 *
 * This is a different concept from "an account is registered": the account id is
 * persisted (`SessionManager`) and survives restarts, while *access to the UI* is
 * runtime-only. QALQON's management screen is sensitive — a child holding the
 * phone must not reach Settings, protection, schedules or the language picker by
 * simply opening the app again — so registration alone must never grant UI access
 * on a later launch.
 *
 * Deliberately **not persisted** (like [SecurityStateHolder]): process death,
 * a task swipe or a device reboot all drop the flag, so the next launch locks the
 * UI again and asks for the PIN. It holds no secret, is never written to
 * DataStore, a Bundle, navigation arguments or logs, and is cleared on logout.
 *
 * The stored PIN material and its verification (PBKDF2 + lockout) stay entirely in
 * the existing account/security architecture; this holder only remembers the
 * outcome of a successful unlock for the current process.
 */
@Singleton
class AppLockState @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)

    /** True while the parent UI may be shown. Starts locked on every process start. */
    val unlocked: StateFlow<Boolean> = _unlocked

    /** Synchronous read, for callers that cannot collect (e.g. a startup decision). */
    fun isUnlocked(): Boolean = _unlocked.value

    /**
     * Marks the UI unlocked. Called only after the existing credential check
     * succeeded: account registration, login, or the startup PIN unlock.
     */
    @Synchronized
    fun onAuthenticated() {
        _unlocked.value = true
    }

    /** Locks the UI again (logout, full reset). The next launch asks for the PIN. */
    @Synchronized
    fun lock() {
        _unlocked.value = false
    }
}
