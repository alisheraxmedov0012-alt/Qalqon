package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-5: usage *snapshots* and the explicit outcome of comparing two of them.
 *
 * Android reports per-package `totalTimeInForeground` for a queried window — a cumulative
 * figure for that window, not a span. Two snapshots of the *same* window can be
 * subtracted to get "what accrued since the last look"; two snapshots of *different*
 * windows cannot, because they are not measuring the same quantity. This file models
 * that distinction in the types rather than leaving it to arithmetic discipline.
 *
 * Pure domain: no Android, no Room, no clock.
 */

/**
 * One observation: what each package had accumulated inside [range] at the moment it was
 * taken.
 *
 * [samples] are always normalized ([AppUsageSamples.aggregate]), so one package appears
 * at most once and no zero rows survive — which is why "has the package been observed"
 * is simply "is it present". A snapshot is a *total per package*, so it carries no
 * [UsageInterval] and cannot be split across midnight; only a single-day window can be
 * attributed to a day (see [UsageSnapshotDeltaEngine]).
 */
class UsageSnapshot private constructor(
    val range: UsageRange,
    val samples: List<AppUsageSample>,
) {

    private val cumulativeByPackage: Map<String, Long> =
        samples.associate { it.packageName to it.foregroundMs }

    /** Packages observed in this snapshot, ordered by name for deterministic output. */
    val packages: List<String> get() = cumulativeByPackage.keys.sorted()

    fun hasPackage(packageName: String): Boolean =
        cumulativeByPackage.containsKey(packageName.trim())

    /** Cumulative foreground time observed for [packageName]; `0` when not observed. */
    fun cumulativeMsFor(packageName: String): Long =
        cumulativeByPackage[packageName.trim()] ?: 0L

    companion object {
        /**
         * Builds a snapshot from raw platform rows.
         *
         * Normalization reuses [AppUsageSamples.aggregate], so duplicate rows for one
         * package are summed (and an overflowing sum is rejected) exactly as everywhere
         * else in the pipeline — there is no second aggregation path.
         */
        fun of(range: UsageRange, reportedSamples: List<AppUsageSample>): UsageSnapshot =
            UsageSnapshot(range, AppUsageSamples.aggregate(reportedSamples))

        /** A window in which nothing was recorded. */
        fun empty(range: UsageRange): UsageSnapshot = UsageSnapshot(range, emptyList())
    }
}

/**
 * What one package contributed when two snapshots were compared.
 *
 * Every variant answers "how much may be added to the child's usage?" via [deltaMs],
 * and that answer is never negative — a decreasing counter cannot be turned into
 * negative usage anywhere in this design.
 */
sealed interface PackageUsageDelta {

    val packageName: String

    /** Usage to account for, `>= 0`. */
    val deltaMs: Long

    /** The counter grew: [deltaMs] is the increase since the previous observation. */
    data class Accumulated(
        override val packageName: String,
        override val deltaMs: Long,
    ) : PackageUsageDelta {
        init {
            require(deltaMs > 0L) { "an Accumulated delta must be positive, was $deltaMs" }
        }
    }

    /** The counter did not move since the previous observation; nothing to account. */
    data class Unchanged(override val packageName: String) : PackageUsageDelta {
        override val deltaMs: Long get() = 0L
    }

    /**
     * The package was not in the previous observation, so there is no baseline to
     * subtract and nothing can be attributed to the interval between the two snapshots.
     *
     * Contributes **zero**. The observed value might have accrued entirely before the
     * previous observation ever happened, so counting it would invent usage; it becomes
     * the baseline for the next comparison instead. A package that first appears here is
     * therefore not counted for this interval — see the limitation note on
     * [UsageSnapshotRecorder].
     */
    data class NewBaseline(
        override val packageName: String,
        val observedMs: Long,
    ) : PackageUsageDelta {
        init {
            require(observedMs > 0L) { "an observed baseline must be positive, was $observedMs" }
        }

        override val deltaMs: Long get() = 0L
    }

    /**
     * The counter went *down*, which cannot be real usage. It means the source restarted:
     * a usage-stats reset, a reinstall, or a different underlying counter.
     *
     * Contributing the observed value is the deliberate, documented choice (Step 1B-5
     * §10): everything the counter now shows accrued after the reset, i.e. after the
     * previous observation, so it is genuinely new usage. It is still never negative, and
     * the reset is visible to the caller rather than silently folded into a delta.
     */
    data class ResetBaseline(
        override val packageName: String,
        val observedMs: Long,
    ) : PackageUsageDelta {
        init {
            require(observedMs > 0L) { "an observed baseline must be positive, was $observedMs" }
        }

        override val deltaMs: Long get() = observedMs
    }
}

/** The outcome of comparing a (possibly absent) previous snapshot with a current one. */
sealed interface UsageSnapshotComparison {

    /**
     * The two observations are the same measurement window, so they were subtracted.
     * [deltas] carries every package observed in the current snapshot, in package order.
     */
    data class Compared(
        val dateKey: String,
        val deltas: List<PackageUsageDelta>,
    ) : UsageSnapshotComparison

    /**
     * The two snapshots describe different windows, so their values are not the same
     * quantity and must not be subtracted. Nothing is accounted; the caller should either
     * re-query the anchored window or treat the current snapshot as the new baseline.
     */
    data object DifferentObservationWindow : UsageSnapshotComparison
}
