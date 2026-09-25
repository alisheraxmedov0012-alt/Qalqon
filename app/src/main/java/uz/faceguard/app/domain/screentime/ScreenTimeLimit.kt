package uz.faceguard.app.domain.screentime

import kotlinx.coroutines.flow.Flow

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
 * Reads and writes configured TOTAL / CATEGORY screen-time limits.
 *
 * Every method is account + child scoped, so one child's limits can never be read as, or
 * written as, another's. Per-app limits are deliberately absent: they live in
 * `child_app_policies` and are managed through `ChildAppPolicyRepository`, so there is
 * exactly one place each kind of limit can be edited.
 */
interface ScreenTimeLimitRepository {

    /** Every configured limit for one child; empty means "nothing configured at all". */
    suspend fun limits(accountId: Long, childId: Long): List<ScreenTimeLimit>

    /**
     * [limits] as an observable stream, so a screen reflects a saved change without
     * re-reading on every recomposition and without polling.
     */
    fun observeLimits(accountId: Long, childId: Long): Flow<List<ScreenTimeLimit>>

    /** The limit for one scope, or `null` when that scope has no configured limit. */
    suspend fun limit(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory? = null,
    ): ScreenTimeLimit?

    /**
     * Stores a limit for one scope, replacing any previous value for it.
     *
     * [limitMinutes] is non-null on purpose: "no limit" is the *absence* of a row (see
     * [delete]), never a stored sentinel. `0` is a real configuration meaning "immediately
     * exceeded", so it is stored as given rather than read as unlimited.
     *
     * The value is validated here with the existing [ScreenTimeLimits.isAccepted] rule, so
     * the domain stays authoritative even if a caller's input layer lets something invalid
     * through: a negative or above-a-day value is rejected instead of being persisted.
     *
     * @throws IllegalArgumentException when [limitMinutes] is not an accepted limit, or the
     *   scope/category combination is not storable.
     */
    suspend fun upsert(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory? = null,
        limitMinutes: Int,
    )

    /**
     * Removes the limit for one scope, and only that one: another category, the total, or
     * another child's configuration is untouched. After this the scope evaluates as
     * unlimited.
     */
    suspend fun delete(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory? = null,
    )
}
