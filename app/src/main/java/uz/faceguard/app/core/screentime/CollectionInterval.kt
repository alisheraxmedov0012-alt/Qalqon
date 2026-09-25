package uz.faceguard.app.core.screentime

/**
 * Phase 4 Step 1B-7: how often screen-time usage is collected.
 *
 * `UsageStatsManager` is a *historical aggregate* source, not a live signal: polling it
 * more often would buy nothing but battery drain, because the number it reports only moves
 * as the child actually uses apps. Ten minutes is the approved interval — frequent enough
 * that "today's usage" is never far behind, sparse enough that a screen-time baseline costs
 * a negligible amount of power.
 *
 * It is a named constant rather than a user setting on purpose: this step is storage and
 * orchestration, and exposing an interval knob would invite tuning the poll instead of the
 * feature. The value is pinned by tests so it cannot drift silently.
 */
object CollectionInterval {
    /** Ten minutes. The only place this duration is written down. */
    const val SCREEN_TIME_COLLECTION_INTERVAL_MS: Long = 10 * 60_000L
}
