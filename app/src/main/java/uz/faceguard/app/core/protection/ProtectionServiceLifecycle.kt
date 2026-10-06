package uz.faceguard.app.core.protection

/**
 * Stage 5: the protection service's restart/removal contract.
 *
 * Kept as pure, Android-free decisions so the strategy can be asserted on the JVM
 * without a running `Service`, and so the intent cannot silently drift.
 */
enum class ServiceRestartMode {
    /** The OS is asked to recreate the service after it is killed (best effort). */
    STICKY,

    /** The OS is never asked to recreate the service. */
    NOT_STICKY,
}

/**
 * The background-runtime restart strategy.
 *
 * QALQON is an always-on parental-control keep-alive the user explicitly enabled,
 * so losing it silently to an OS low-memory kill is the failure this stage cares
 * about. [ServiceRestartMode.STICKY] makes the system *may* recreate the service
 * (and, with it, a fresh app-scoped runtime) after such a kill.
 *
 * This is deliberately **not** a claim of guaranteed background operation:
 *  - a sticky restart is best-effort — the system may still decline it;
 *  - it never happens after a user **force stop** (Android does not restart a
 *    force-stopped package) — that remains an accepted platform limitation;
 *  - it never happens after the user disables protection, because disabling goes
 *    through the runtime, which stops the service (a stopped service is not
 *    restarted).
 *
 * Safety: the app is single-process and every restart path is idempotent, so a
 * recreate can never produce a second runtime, camera session, analyzer, overlay
 * or notification — it rebuilds the same singletons from persisted settings.
 */
object ProtectionServiceLifecyclePolicy {

    val restartMode: ServiceRestartMode = ServiceRestartMode.STICKY

    /**
     * Recents-task removal must not end protection.
     *
     * QALQON is a parental-control keep-alive; a child swiping the app away is not
     * a legitimate way to disable it. Android's default for a started (non-bound)
     * service is to keep it running and merely notify it via `onTaskRemoved`, so
     * the correct product behaviour is to do nothing there. Protection ends only
     * when the user turns it off, which the runtime drives.
     */
    fun shouldStopOnTaskRemoved(): Boolean = false

    /**
     * True when a **system-initiated** restart (a `null` intent) should end the
     * service instead of continuing: i.e. protection is no longer wanted for the
     * persisted intent. Only evaluated once the runtime has actually observed the
     * persisted settings, so a restart cannot race the first DataStore read.
     */
    fun shouldSelfStopAfterSystemRestart(
        protectionEnabled: Boolean,
        accountId: Long?,
    ): Boolean = !ProtectionServicePolicy.shouldRun(protectionEnabled, accountId)
}

/**
 * Stage 5: bounded exponential backoff for camera-session recovery.
 *
 * A lost camera binding is retried a bounded number of times with growing delays,
 * so a genuinely unavailable camera (in use by another app, a permission race, a
 * hardware fault) cannot drive an unbounded tight retry loop that wastes battery
 * and CPU. A successful rebind resets the counter, so a later, independent
 * interruption starts from the shortest delay again.
 *
 * Pure and deterministic: no timers, no Android, no coroutines. The caller owns
 * the scheduling; this only answers "how long until the next attempt, or never".
 */
class CameraRecoveryBackoff(
    private val delaysMs: List<Long> = DEFAULT_DELAYS_MS,
) {
    private var attempt = 0

    /** Attempts used since the last [reset]; exposed for diagnostics and tests. */
    val attempts: Int get() = attempt

    /** True once every bounded attempt has been used and no retry remains. */
    val isExhausted: Boolean get() = attempt >= delaysMs.size

    /**
     * Consumes one attempt and returns the delay before it, or `null` when the
     * bound is reached (the caller must stop retrying until [reset]).
     */
    fun nextDelayMs(): Long? = if (attempt < delaysMs.size) delaysMs[attempt++] else null

    /** Clears the counter after a successful rebind (or an explicit user resume). */
    fun reset() { attempt = 0 }

    companion object {
        /**
         * 1s, 2s, 5s, 15s, 30s — five attempts over ~53s. Long enough to ride out a
         * phone call or another camera app, short enough to stay responsive, and
         * bounded so it can never become a permanent busy loop.
         */
        val DEFAULT_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L)
    }
}
