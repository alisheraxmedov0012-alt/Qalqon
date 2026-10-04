package uz.faceguard.app.core.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 *
 * **Re-lock on background.** A successful unlock is not left valid for the whole
 * process lifetime: when the app leaves the foreground the UI re-locks once a
 * short grace period has elapsed ([DEFAULT_BACKGROUND_GRACE_MILLIS]), so a quick
 * app switch does not interrupt the parent, while a phone handed over or pocketed
 * comes back to the credential gate. The timer lives only in this holder and is
 * driven by the Activity lifecycle (`MainActivity.onStart`/`onStop`); nothing here
 * knows about navigation or protection — the existing `lockRedirectFor` gate turns
 * the locked state into the credential-screen redirect.
 */
@Singleton
class AppLockState @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)

    /** True while the parent UI may be shown. Starts locked on every process start. */
    val unlocked: StateFlow<Boolean> = _unlocked

    /** The pending re-lock timer, if one is scheduled. */
    private var pendingLock: Job? = null

    /** Synchronous read, for callers that cannot collect (e.g. a startup decision). */
    fun isUnlocked(): Boolean = _unlocked.value

    /**
     * Marks the UI unlocked. Called only after the existing credential check
     * succeeded: account registration, login, or the startup PIN unlock.
     *
     * A pending re-lock is cancelled: a fresh authentication always wins.
     */
    @Synchronized
    fun onAuthenticated() {
        cancelPendingLockLocked()
        _unlocked.value = true
    }

    /** Locks the UI again (logout, full reset, or the background grace elapsing). */
    @Synchronized
    fun lock() {
        cancelPendingLockLocked()
        lockNow()
    }

    /**
     * Schedules a re-lock [graceMillis] from now, replacing any earlier pending
     * timer. Called when the app goes to the background; the timer fires while the
     * app is away, so returning after the grace lands on the PIN gate.
     *
     * The caller owns [scope] (the Activity lifecycle scope), so a destroyed host
     * simply cancels the timer — process death already starts locked.
     */
    @Synchronized
    fun lockAfter(scope: CoroutineScope, graceMillis: Long) {
        cancelPendingLockLocked()
        pendingLock = scope.launch {
            delay(graceMillis)
            // Clear the reference before locking so lock() cannot cancel this job
            // from inside itself.
            synchronized(this@AppLockState) { pendingLock = null }
            lockNow()
        }
    }

    /**
     * Cancels a pending re-lock. Called when the app returns to the foreground
     * within the grace window, so a brief switch keeps the parent's session.
     */
    @Synchronized
    fun cancelPendingLock() {
        cancelPendingLockLocked()
    }

    private fun cancelPendingLockLocked() {
        pendingLock?.cancel()
        pendingLock = null
    }

    private fun lockNow() {
        _unlocked.value = false
    }

    companion object {
        /**
         * How long the UI stays unlocked after the app leaves the foreground.
         * Long enough that switching away to copy a phone number and back is not
         * punished, short enough that a handed-over device re-arms the PIN gate.
         */
        const val DEFAULT_BACKGROUND_GRACE_MILLIS: Long = 15_000L
    }
}
