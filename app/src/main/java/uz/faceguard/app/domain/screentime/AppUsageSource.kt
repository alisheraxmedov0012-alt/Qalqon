package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-4: the seam between the device's usage statistics and the Screen Time
 * accounting layer.
 *
 * Android's `UsageStatsManager` cannot say *when* a package was in the foreground — it
 * reports an aggregate `totalTimeInForeground` for a queried window. This layer therefore
 * models an **aggregate sample** ([AppUsageSample]), deliberately not a [UsageInterval]:
 * turning a total into a `[start, end)` pair would invent timestamps that never existed
 * and would silently corrupt midnight splitting. A genuine interval exists only when a
 * source actually has start/end timestamps; nothing here fabricates one.
 *
 * The source also knows nothing above it: no account, no child, no category, no
 * persistence. Which child a device's usage belongs to, which apps are controlled, and
 * how a sample becomes a dated delta are decisions for the orchestration layer — so this
 * interface cannot leak them by construction.
 */

/** Whether the app is allowed to read device usage statistics at all. */
enum class UsageAccessState { AVAILABLE, UNAVAILABLE }

/**
 * Aggregate foreground time of one package within the queried window.
 *
 * This is a *total*, not a span: [foregroundMs] is how long the package was in the
 * foreground somewhere inside the window, not when. Do not build a [UsageInterval] from
 * it — see the note on this file.
 */
data class AppUsageSample(val packageName: String, val foregroundMs: Long) {

    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(foregroundMs >= 0L) { "foregroundMs must not be negative, was $foregroundMs" }
    }
}

/**
 * The outcome of a usage query.
 *
 * Access is part of the type rather than an exception, so a caller cannot mistake
 * "we are not allowed to read usage" for "the device recorded nothing". [Unavailable]
 * also covers a platform that refused the query (for example access revoked while the
 * call was in flight); [Available] with an empty list means the query genuinely ran and
 * nothing was recorded in that window.
 */
sealed interface AppUsageQueryResult {
    data class Available(val samples: List<AppUsageSample>) : AppUsageQueryResult
    data object Unavailable : AppUsageQueryResult
}

/**
 * Reads package-level foreground usage for an explicit window.
 *
 * The window is caller-defined ([UsageRange] is half-open `[start, end)` and already
 * rejects negative, empty and reversed ranges) — the source has no notion of "today",
 * "yesterday" or "this week", and never reads the clock itself. Splitting a span at
 * midnight is `UsageAccounting.splitAtMidnight`'s job, not this one's.
 */
interface AppUsageSource {

    /** Cheap capability probe; safe to call before every query. */
    fun usageAccess(): UsageAccessState

    /** Aggregated foreground usage per package inside [range]; never writes anything. */
    suspend fun queryUsage(range: UsageRange): AppUsageQueryResult
}
