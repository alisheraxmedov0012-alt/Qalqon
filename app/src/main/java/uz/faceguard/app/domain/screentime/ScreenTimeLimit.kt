package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1C: a configured screen-time limit, as the evaluator sees it.
 *
 * This is a *read model* over the existing `child_screen_time_limits` table — it adds no
 * storage and no second source of truth. Only the scopes that table owns appear here:
 *
 *  - [LimitScope.TOTAL] — the child's whole day, stored with an empty category;
 *  - [LimitScope.CATEGORY] — one [AppCategory], stored by its stable name.
 *
 * Per-app limits deliberately do **not** live here: they already live in
 * `child_app_policies.dailyLimitMinutes` and are read through the existing
 * `ChildAppPolicyRepository`, so there is exactly one place a per-app limit can be edited.
 *
 * [limitMinutes] keeps the project's existing representation (`null` = unlimited,
 * `0` = immediately exceeded), and it is carried through *unchanged* — including a value
 * that is out of range. Classifying a corrupt value is the evaluator's job, so it stays
 * visible instead of being silently repaired here.
 */
data class ScreenTimeLimit(
    val accountId: Long,
    val childId: Long,
    val scope: LimitScope,
    /** The category this limit applies to; `null` for [LimitScope.TOTAL]. */
    val category: AppCategory?,
    /** `null` = unlimited, `0` = immediately exceeded, `1..1440` = daily quota (minutes). */
    val limitMinutes: Int?,
) {

    init {
        require(accountId > 0L) { "accountId must be positive, was $accountId" }
        require(childId > 0L) { "childId must be positive, was $childId" }
        require(scope != LimitScope.APP) {
            "per-app limits live in child_app_policies, not in a screen-time limit row"
        }
        require((scope == LimitScope.CATEGORY) == (category != null)) {
            "a TOTAL limit has no category and a CATEGORY limit must have one (scope=$scope)"
        }
    }
}

/**
 * Reads configured TOTAL / CATEGORY screen-time limits.
 *
 * Every method is account + child scoped, so one child's limits can never be read as
 * another's. Read-only on purpose: this step evaluates limits, it does not configure them.
 */
interface ScreenTimeLimitRepository {

    /** Every configured limit for one child; empty means "nothing configured at all". */
    suspend fun limits(accountId: Long, childId: Long): List<ScreenTimeLimit>

    /** The limit for one scope, or `null` when that scope has no configured limit. */
    suspend fun limit(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory? = null,
    ): ScreenTimeLimit?
}
