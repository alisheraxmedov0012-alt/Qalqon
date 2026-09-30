package uz.faceguard.app.feature.home

import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluation

/**
 * Phase 4 Step 2: what the screens show about screen time.
 *
 * These are presentation models only. They are built *from* the existing
 * [ScreenTimeLimitEvaluation] and copy its numbers verbatim — nothing here recomputes
 * `remaining = limit - used` or `used >= limit`, because the evaluator (Step 1C) is the single
 * authority on those. A row is a view of a decision already made, not a second decision.
 *
 * No Room entity, no DataStore value and no evaluator input type reaches Compose directly, and
 * nothing user-facing is baked in: labels are carried as identities
 * ([ScreenTimeInfoLabel]) that the UI turns into localized text, and durations are exposed as
 * exact milliseconds.
 */

/** What one row is about, so the UI can supply the localized name. */
sealed interface ScreenTimeInfoLabel {
    data object Total : ScreenTimeInfoLabel
    data class Category(val category: AppCategory) : ScreenTimeInfoLabel
    data class App(val packageName: String) : ScreenTimeInfoLabel
}

/**
 * One scope's facts, exactly as the evaluator reported them.
 *
 * [limitMinutes] and [remainingMs] are `null` together for an unlimited scope, which is what
 * lets the UI show "No limit" instead of `0 min` — the two must never be conflated, because
 * `0` is a real configuration meaning "already used up".
 */
data class ScreenTimeInfoRow(
    val label: ScreenTimeInfoLabel,
    val usedMs: Long,
    val limitMinutes: Int?,
    val remainingMs: Long?,
    val hasLimit: Boolean,
    val exceeded: Boolean,
    /** True when the stored limit was not a valid configuration; shown rather than hidden. */
    val invalidLimit: Boolean = false,
)

/**
 * Copies an evaluation into a row without touching its arithmetic.
 *
 * The scope decides the label, so a row can never claim to be a *time* limit for a scope the
 * evaluator reported as unlimited.
 */
fun ScreenTimeLimitEvaluation.toInfoRow(): ScreenTimeInfoRow = ScreenTimeInfoRow(
    label = when (scope) {
        LimitScope.APP -> ScreenTimeInfoLabel.App(packageName.orEmpty())
        LimitScope.CATEGORY -> ScreenTimeInfoLabel.Category(
            category ?: AppCategory.OTHER,
        )
        LimitScope.TOTAL -> ScreenTimeInfoLabel.Total
    },
    usedMs = usedMs,
    limitMinutes = limitMinutes,
    remainingMs = remainingMs,
    hasLimit = hasLimit,
    exceeded = exceeded,
    invalidLimit = invalidLimitMinutes != null,
)

/** How the summary section states itself. Each value is a distinct situation the parent sees. */
enum class ScreenTimeSummaryStatus {
    LOADING,

    /** Nothing to show: no signed-in account. */
    NO_ACCOUNT,

    /** No screen-time child is selected, so there is no target to report on. */
    NO_TARGET,

    /**
     * Usage Access is not granted, so usage genuinely cannot be read. Deliberately distinct
     * from "no usage yet": showing `0 min` here would claim the child used nothing.
     */
    USAGE_UNAVAILABLE,

    /** Data was read successfully; the rows carry the facts (zero usage is a valid value). */
    READY,

    /** Reading failed; reported instead of being shown as "no limit" or zero usage. */
    ERROR,
}

/**
 * The screen-time summary for one child on one day.
 *
 * [status] and the rows together are enough for the UI; there is no combined "winner" field,
 * because the evaluator reports each scope independently (total, categories, apps) and a
 * later enforcement step — not the UI — decides what to do when one of them is reached.
 */
data class ScreenTimeSummaryUiState(
    val status: ScreenTimeSummaryStatus = ScreenTimeSummaryStatus.LOADING,
    val childId: Long? = null,
    val childName: String? = null,
    /** The local day these facts are for (`yyyy-MM-dd`), or `null` before it is known. */
    val dateKey: String? = null,
    val total: ScreenTimeInfoRow? = null,
    val categories: List<ScreenTimeInfoRow> = emptyList(),
    val errorMessageRes: Int? = null,
) {
    /** True when a child is targeted and usage could be read. */
    val hasData: Boolean get() = status == ScreenTimeSummaryStatus.READY

    /** The categories the child actually has usage in, in the classifier's order. */
    val usedCategories: List<ScreenTimeInfoRow> get() = categories.filter { it.usedMs > 0L || it.hasLimit }
}

/**
 * Converts exact milliseconds into whole minutes and hours for display.
 *
 * One formatter for every duration the screens show, so `90 seconds` can never be rendered as
 * `90 minutes`: values are converted once, by division, and the domain keeps milliseconds.
 * Deliberately pure and Android-free, so it is unit-testable and identical everywhere.
 */
object ScreenTimeDurationFormat {

    /** Milliseconds in one minute; the only conversion factor this layer needs. */
    const val MS_PER_MINUTE = 60_000L

    private const val MINUTES_PER_HOUR = 60L

    /** Whole minutes, rounded down: 90 s is 1 min, and 59 s is 0 min. */
    fun minutesOf(ms: Long): Long = (ms.coerceAtLeast(0L)) / MS_PER_MINUTE

    /** Whole minutes and the remaining whole hours, for a readable "1 h 30 min". */
    data class Parts(val hours: Long, val minutes: Long) {
        /** True when only minutes need showing (under an hour). */
        val isMinutesOnly: Boolean get() = hours == 0L
    }

    fun partsOf(ms: Long): Parts {
        val totalMinutes = minutesOf(ms)
        return Parts(hours = totalMinutes / MINUTES_PER_HOUR, minutes = totalMinutes % MINUTES_PER_HOUR)
    }
}
