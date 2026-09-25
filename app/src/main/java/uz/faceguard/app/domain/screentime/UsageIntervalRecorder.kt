package uz.faceguard.app.domain.screentime

/**
 * A usage span that has been started but not yet closed.
 *
 * [startedAtElapsedMs] is a monotonic reading and is only ever subtracted from another
 * one; [startedAtWallClockMs] anchors the span to a calendar day.
 */
data class UsageSession(
    val packageName: String,
    val category: AppCategory,
    val startedAtWallClockMs: Long,
    val startedAtElapsedMs: Long,
)

/**
 * Phase 4 Step 1B-3: turns "this app became foreground" / "it stopped" into a
 * [UsageInterval] whose duration is measured, not inferred.
 *
 * The duration is always the difference of two [ElapsedTimeSource] readings, so a
 * wall-clock adjustment (a manual change, a timezone change, an NTP correction) cannot
 * invent, shrink or grow a duration. The wall clock is used only for the span's start
 * anchor, which is what decides the calendar day.
 *
 * Pure domain code: no Android, no I/O. The source and the wall clock are injected.
 */
class UsageIntervalRecorder(
    private val elapsedTimeSource: ElapsedTimeSource,
    private val wallClock: () -> Long,
) {

    fun begin(packageName: String, category: AppCategory): UsageSession = UsageSession(
        packageName = packageName,
        category = category,
        startedAtWallClockMs = wallClock(),
        startedAtElapsedMs = elapsedTimeSource.elapsedRealtimeMs(),
    )

    /**
     * Closes [session].
     *
     * Returns `null` when no positive duration has measurably elapsed (a span that was
     * opened and closed in the same instant, or a monotonic reading that did not move):
     * there is nothing to account for, and an interval is not allowed to be empty.
     *
     * A span of one day or more cannot be expressed as an interval and is rejected —
     * the monitoring layer owns closing a session well before that.
     */
    fun stop(session: UsageSession): UsageInterval? {
        val elapsedMs = elapsedTimeSource.elapsedRealtimeMs() - session.startedAtElapsedMs
        if (elapsedMs <= 0L) return null
        return UsageInterval.of(
            packageName = session.packageName,
            category = session.category,
            startTimeMs = session.startedAtWallClockMs,
            elapsedMs = elapsedMs,
        )
    }
}
