package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-3: the accounting vocabulary.
 *
 * These are plain value types: no Android, no Room, no clock, no I/O. A monitoring
 * layer (a later step) produces [UsageInterval]s; the accounting layer turns them into
 * [UsageDelta]s and hands those to `ScreenTimeUsageRepository`.
 *
 * All durations are exact `Long` milliseconds. `startTimeMs`/`endTimeMs` are wall-clock
 * epoch milliseconds, used only to decide *which calendar day* a span belongs to —
 * never to measure how long it lasted (see [ElapsedTimeSource]).
 */

/**
 * A non-empty half-open wall-clock span `[startTimeMs, endTimeMs)`.
 *
 * Zero-length and reversed spans are rejected on construction, so no downstream code
 * has to defend against a negative or empty duration. Bounds are validated only here;
 * the one-day bound lives on [UsageInterval], which is what the repository accepts.
 */
data class UsageRange(val startTimeMs: Long, val endTimeMs: Long) {

    init {
        require(startTimeMs >= 0L) { "startTimeMs must not be negative, was $startTimeMs" }
        require(endTimeMs > startTimeMs) {
            "endTimeMs must be greater than startTimeMs, was $startTimeMs..$endTimeMs"
        }
    }

    /** Positive by construction, so this cannot underflow. */
    val durationMs: Long get() = endTimeMs - startTimeMs

    /** True when this span already includes [other]'s start, i.e. they overlap or touch. */
    fun reaches(other: UsageRange): Boolean = other.startTimeMs <= endTimeMs
}

/**
 * One app's usage from [startTimeMs] to [endTimeMs].
 *
 * [elapsedMs] is derived from the bounds, so a span is always self-consistent. Use
 * [of] when the duration comes from a monotonic measurement rather than from the
 * difference of two wall-clock readings.
 *
 * The duration must be positive and at most one day
 * ([ScreenTimeUsageRepository.MAX_DELTA_MS]) — the same bound the repository enforces,
 * so an interval that exists can always be written. A span longer than a day is not
 * silently truncated: it is rejected, and splitting such a span is a monitoring
 * concern (later step), not an accounting one.
 */
data class UsageInterval(
    val packageName: String,
    val category: AppCategory,
    val startTimeMs: Long,
    val endTimeMs: Long,
) {

    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(startTimeMs >= 0L) { "startTimeMs must not be negative, was $startTimeMs" }
        require(endTimeMs > startTimeMs) {
            "elapsed time must be positive, was $startTimeMs..$endTimeMs"
        }
        val elapsed = endTimeMs - startTimeMs
        require(elapsed <= ScreenTimeUsageRepository.MAX_DELTA_MS) {
            "elapsed time must not exceed one day (${ScreenTimeUsageRepository.MAX_DELTA_MS}), was $elapsed"
        }
    }

    val elapsedMs: Long get() = endTimeMs - startTimeMs

    val range: UsageRange get() = UsageRange(startTimeMs, endTimeMs)

    companion object {
        /**
         * Builds an interval from a start and a *measured* duration, so a monotonic
         * reading can be used without ever measuring with the wall clock.
         *
         * Rejects a non-positive duration and a `startTimeMs + elapsedMs` that would
         * overflow `Long` (the wrapped value would otherwise be a plausible-looking
         * but wrong end time).
         */
        fun of(
            packageName: String,
            category: AppCategory,
            startTimeMs: Long,
            elapsedMs: Long,
        ): UsageInterval {
            require(elapsedMs > 0L) { "elapsedMs must be positive, was $elapsedMs" }
            val endTimeMs = try {
                Math.addExact(startTimeMs, elapsedMs)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException(
                    "startTimeMs + elapsedMs overflows Long ($startTimeMs + $elapsedMs)",
                )
            }
            return UsageInterval(packageName, category, startTimeMs, endTimeMs)
        }
    }
}

/**
 * Usage that has been assigned to exactly one calendar day and one app, ready to be
 * persisted: the smallest unit `ScreenTimeUsageRepository.addUsage` accepts.
 *
 * A span that crosses local midnight becomes two deltas (see `UsageAccounting`), which
 * is why the day key lives here rather than on [UsageInterval].
 *
 * The bounds mirror the repository contract exactly (positive, at most one day, valid
 * `yyyy-MM-dd` key, non-blank package), so an accounting result that exists can always
 * be written and can never be rejected halfway through a batch.
 */
data class UsageDelta(
    val dateKey: String,
    val packageName: String,
    val category: AppCategory,
    val elapsedMs: Long,
) {

    init {
        require(UsageDateKey.isValid(dateKey)) {
            "dateKey must be canonical yyyy-MM-dd, was '$dateKey'"
        }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(elapsedMs > 0L) { "elapsedMs must be positive, was $elapsedMs" }
        require(elapsedMs <= ScreenTimeUsageRepository.MAX_DELTA_MS) {
            "elapsedMs must not exceed one day (${ScreenTimeUsageRepository.MAX_DELTA_MS}), was $elapsedMs"
        }
    }
}
