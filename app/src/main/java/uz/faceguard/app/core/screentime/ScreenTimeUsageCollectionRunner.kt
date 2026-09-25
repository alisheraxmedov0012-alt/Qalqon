package uz.faceguard.app.core.screentime

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.faceguard.app.domain.screentime.ElapsedTimeSource
import uz.faceguard.app.domain.screentime.ScreenTimeUsageCollectionCoordinator

/**
 * Phase 4 Step 1B-7: the periodic driver for screen-time usage collection.
 *
 * Ownership is deliberately narrow: this class owns **one** coroutine loop and nothing
 * else. The lifecycle belongs to `ProtectionRuntime`, which calls [start]/[stop] from its
 * activate/deactivate transitions, so there is exactly one scheduler in the app — no
 * second service, no WorkManager, no alarm, and `ProtectionForegroundService` stays
 * lifecycle-only.
 *
 * Battery and safety properties:
 *  - **one loop, ever.** [start] is idempotent (a live [job] means a loop is already
 *    running), so a repeated runtime start or a service restart cannot stack collectors.
 *  - **no overlap.** The loop awaits each collection before the next [delay], and the
 *    coordinator serializes collections, so two ticks can never account the same interval.
 *  - **cancellable.** [stop] cancels the job and drops the reference, so a stopped
 *    collector never keeps writing and nothing is leaked.
 *  - **the first tick runs immediately** rather than after a full interval, so a fresh
 *    process establishes its baseline at once. That is safe: if a checkpoint already
 *    exists the recorder turns the tick into a zero delta, so an immediate tick can never
 *    double-count.
 *  - **a failure never ends collection.** The coordinator already converts errors into an
 *    outcome; the extra guard here means even a fault thrown outside it skips one tick
 *    instead of silently stopping collection for the rest of the session.
 *
 * Logging is metadata only — the outcome, the package count and how long the tick took.
 * No package names, no account or child identifiers.
 */
@Singleton
class ScreenTimeUsageCollectionRunner @Inject constructor(
    private val coordinator: ScreenTimeUsageCollectionCoordinator,
    /** The existing monotonic clock, reused so tick timing needs no second time source. */
    private val elapsedTimeSource: ElapsedTimeSource,
) {

    /**
     * The interval the loop sleeps between ticks. Defaults to the named product constant;
     * the secondary constructor lets a test drive the loop at a short cadence instead of
     * waiting ten minutes.
     */
    private var intervalMs: Long = CollectionInterval.SCREEN_TIME_COLLECTION_INTERVAL_MS

    internal constructor(
        coordinator: ScreenTimeUsageCollectionCoordinator,
        elapsedTimeSource: ElapsedTimeSource,
        intervalMs: Long,
    ) : this(coordinator, elapsedTimeSource) {
        this.intervalMs = intervalMs
    }

    private var job: Job? = null

    /** The interval a running loop sleeps between ticks. */
    val interval: Long get() = intervalMs

    /** True while a collection loop is running. */
    val running: Boolean get() = job?.isActive == true

    /**
     * How many loops this runner has started. The idempotency guard keeps this at one per
     * session however often [start] is called, which is what "no duplicate loops" means —
     * so it is the observable evidence for that guarantee.
     */
    var loopsStarted: Int = 0
        private set

    /** The most recent outcome, for status and diagnostics. Never contains package data. */
    var lastOutcome: ScreenTimeUsageCollectionCoordinator.Outcome? = null
        private set

    /** Starts the loop in [scope] if it is not already running. Safe to call repeatedly. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        loopsStarted++
        job = scope.launch {
            while (isActive) {
                // A tick must never be able to end collection: neither a platform fault nor
                // diagnostics may silently stop screen-time tracking for the session.
                runCatching { collectOnce() }
                delay(intervalMs)
            }
        }
    }

    /**
     * Cancels the loop and forgets it. Safe to call when not running, and safe to be
     * followed by [start]: a rapid stop/start yields exactly one new loop.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun collectOnce() {
        val startedAt = elapsedTimeSource.elapsedRealtimeMs()
        val outcome = runCatching { coordinator.collect() }
            .getOrElse { error ->
                ScreenTimeUsageCollectionCoordinator.Outcome.Failed(error::class.simpleName ?: "error")
            }
        lastOutcome = outcome
        val elapsedMs = elapsedTimeSource.elapsedRealtimeMs() - startedAt

        when (outcome) {
            // Normal states, not problems: a periodic line for these would only be noise.
            ScreenTimeUsageCollectionCoordinator.Outcome.NoAccount,
            ScreenTimeUsageCollectionCoordinator.Outcome.NoActiveChild,
            -> Unit

            is ScreenTimeUsageCollectionCoordinator.Outcome.Collected -> Log.i(
                TAG,
                "collection ok: packages=${outcome.packagesObserved} " +
                    "accountedMs=${outcome.accountedMs} baselineOnly=${outcome.baselineOnly} " +
                    "tookMs=$elapsedMs",
            )

            is ScreenTimeUsageCollectionCoordinator.Outcome.ActiveChildNotOwned -> Log.w(
                TAG,
                "collection skipped: stored screen-time target is not a child of this account",
            )

            ScreenTimeUsageCollectionCoordinator.Outcome.UsageAccessUnavailable -> Log.i(
                TAG,
                "collection skipped: usage access unavailable (retryable)",
            )

            ScreenTimeUsageCollectionCoordinator.Outcome.WindowNotAttributable -> Log.w(
                TAG,
                "collection skipped: window not attributable to one day",
            )

            is ScreenTimeUsageCollectionCoordinator.Outcome.Failed -> Log.w(
                TAG,
                "collection failed: ${outcome.reason} (checkpoint unchanged, retryable)",
            )
        }
    }

    private companion object {
        const val TAG = "ScreenTimeCollection"
    }
}
