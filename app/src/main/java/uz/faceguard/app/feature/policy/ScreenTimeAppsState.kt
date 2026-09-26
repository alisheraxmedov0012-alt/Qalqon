package uz.faceguard.app.feature.policy

import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluation
import uz.faceguard.app.feature.home.ScreenTimeInfoLabel
import uz.faceguard.app.feature.home.ScreenTimeInfoRow
import uz.faceguard.app.feature.home.toInfoRow

/**
 * Phase 4 Step 3: today's screen-time information for the apps on the child-policy screen.
 *
 * It carries one [ScreenTimeInfoRow] per package — the same row model the Home summary uses,
 * so a per-app row and a total/category row cannot drift apart in meaning — plus the states
 * that are *not* data: still loading, usage access unavailable, or a read failure. Keeping
 * those separate is what stops "we cannot read usage" from being rendered as "used 0 min".
 *
 * The rows are keyed by the canonical package name, which is the identity the app policy
 * already uses.
 */
data class ScreenTimeAppsUiState(
    val loading: Boolean = false,
    /**
     * Whether device usage can be read at all. When false the rows say nothing about usage,
     * because there is nothing to say.
     */
    val usageAvailable: Boolean = true,
    val errorMessageRes: Int? = null,
    val rows: Map<String, ScreenTimeInfoRow> = emptyMap(),
) {
    /** The row for one app, or `null` when nothing is known about it (no usage, no limit). */
    fun rowFor(packageName: String): ScreenTimeInfoRow? = rows[packageName]
}

/**
 * Turns one app evaluation into a row, reusing Step 2's mapping so the per-app presentation
 * inherits its semantics exactly — including that an unlimited app reports no limit and no
 * remaining time rather than `0 min`, and that a corrupt stored limit is flagged rather than
 * being clamped or read as unlimited.
 *
 * Only [LimitScope.APP] evaluations are accepted: a per-app row must never be built from a
 * total or category evaluation, so an app can never be shown a limit that belongs to a broader
 * scope.
 */
internal fun ScreenTimeLimitEvaluation.toAppRow(): ScreenTimeInfoRow? {
    if (scope != LimitScope.APP) return null
    val row = toInfoRow()
    return if (row.label is ScreenTimeInfoLabel.App) row else null
}
