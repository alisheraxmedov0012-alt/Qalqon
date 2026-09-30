package uz.faceguard.app.domain.screentime

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository

/**
 * Phase 4 Step 1C: one scope's limit evaluation — the facts, not a decision.
 *
 * The arithmetic itself is not re-implemented here: [status] is the existing [LimitStatus]
 * produced by [limitStatus], so the `used >= limit` boundary, the clamped
 * `remainingMs` and the unlimited representation stay in the single place they already
 * live and are shared with the policy engine.
 *
 * Semantics (approved for this step, and matching [limitStatus]):
 *  - configured limit, `used < limit` → `exceeded = false`, `remainingMs = limit - used`
 *  - configured limit, `used >= limit` → `exceeded = true`, `remainingMs = 0`
 *  - no configured limit → `hasLimit = false`, `remainingMs = null`, `exceeded = false`
 *  - a stored limit that is not a valid configuration (see [invalidLimitMinutes]) is
 *    reported as **invalid**: it is never silently read as unlimited, as zero, or clamped,
 *    because hiding a corrupt limit would silently change a child's protection.
 *
 * [remainingMs] is never negative.
 */
data class ScreenTimeLimitEvaluation(
    val scope: LimitScope,
    val status: LimitStatus,
    /** The app this evaluation is about; set only for [LimitScope.APP]. */
    val packageName: String? = null,
    /** The category this evaluation is about; set only for [LimitScope.CATEGORY]. */
    val category: AppCategory? = null,
    /**
     * The stored limit in minutes when it is not a valid configuration (negative, or above
     * a day), otherwise `null`. Present so a corrupt value is visible to callers instead of
     * being silently reinterpreted.
     */
    val invalidLimitMinutes: Int? = null,
) {

    /** True when a valid limit is configured for this scope. */
    val hasLimit: Boolean get() = invalidLimitMinutes == null && !status.unlimited

    /** True when the scope is configured, valid and reached. Never true for unlimited. */
    val exceeded: Boolean get() = hasLimit && status.exceeded

    /** Usage actually accounted for this scope on the evaluated day. */
    val usedMs: Long get() = status.usedMs

    /** The configured limit in minutes, or `null` when unlimited or invalid. */
    val limitMinutes: Int? get() = if (invalidLimitMinutes != null) null else status.limitMinutes

    /** Time left before the limit is reached; `null` when unlimited or invalid, never negative. */
    val remainingMs: Long? get() = if (invalidLimitMinutes != null) null else status.remainingMs
}

/**
 * Phase 4 Step 1C: evaluates already-accounted usage against already-configured limits.
 *
 * ```
 * usage (ScreenTimeUsageRepository)  ┐
 * TOTAL/CATEGORY limits (existing)   ├─▶ ScreenTimeLimitEvaluator ─▶ facts per scope
 * per-app limit (existing policies)  ┘
 * ```
 *
 * What it is: a deterministic, testable domain service that answers "how much is left, and
 * has this scope been reached?" for one account + child + day.
 *
 * What it deliberately is **not**:
 *  - **not enforcement.** It returns facts and blocks nothing. Linking this to
 *    `ProtectionEngine`, overlays, accessibility or notifications is a later step.
 *  - **not a precedence rule.** [evaluateAll] returns every scope's result independently; a
 *    caller must not expect a "winner", because which limit should win is enforcement
 *    policy and is out of scope here.
 *  - **not a second source of truth.** Limits come from `child_screen_time_limits` (via
 *    [ScreenTimeLimitRepository]) and `child_app_policies` (via [ChildAppPolicyRepository]);
 *    usage comes from [ScreenTimeUsageRepository]; categories from the existing
 *    [AppCategories]. Nothing is duplicated or recomputed.
 *  - **not schedule-aware.** There is no timer and no midnight reset: the caller names the
 *    `dateKey`, and only that day is evaluated. The engine never reads a clock.
 *  - **Android-free.** No `Context`, no `UsageStatsManager`, no Compose, no Room and no
 *    DataStore — only domain abstractions.
 *
 * Errors are not swallowed: a failing repository propagates, so a data failure is never
 * reported as "no limit" or as zero usage.
 */
@Singleton
class ScreenTimeLimitEvaluator @Inject constructor(
    private val usageRepository: ScreenTimeUsageRepository,
    private val limitRepository: ScreenTimeLimitRepository,
    private val appPolicyRepository: ChildAppPolicyRepository,
) {

    /**
     * The child's whole day against the configured TOTAL limit.
     *
     * @param dateKey the local day to evaluate (`yyyy-MM-dd`); validated, so a malformed key
     *   fails fast instead of evaluating an unknown day.
     */
    suspend fun evaluateTotal(accountId: Long, childId: Long, dateKey: String): ScreenTimeLimitEvaluation {
        requireValidTarget(accountId, childId, dateKey)
        val stored = limitRepository.limit(accountId, childId, LimitScope.TOTAL)
        val usedMs = usageRepository.getTotalUsageMs(accountId, childId, dateKey)
        return evaluation(LimitScope.TOTAL, stored?.limitMinutes, usedMs)
    }

    /**
     * One package's usage on the evaluated day against its configured per-app limit.
     *
     * The limit is the existing [AppPolicy] for this child and package. Only
     * [AppPolicyMode.LIMIT] carries a *time* limit — an `ALLOW` or `BLOCK` policy is not a
     * daily allowance, so it evaluates as unlimited here, exactly as the policy engine
     * treats it. A `LIMIT` policy with no configured minutes is unlimited (the existing
     * `dailyLimitMinutes == null` representation).
     */
    suspend fun evaluateApp(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String,
    ): ScreenTimeLimitEvaluation {
        requireValidTarget(accountId, childId, dateKey)
        require(packageName.isNotBlank()) { "packageName must not be blank" }

        val limitMinutes = appPolicyRepository.policyFor(accountId, childId, packageName.trim())
            ?.takeIf { it.mode == AppPolicyMode.LIMIT }
            ?.dailyLimitMinutes
        val usedMs = usageRepository.getAppUsageMs(accountId, childId, dateKey, packageName.trim())
        return evaluation(LimitScope.APP, limitMinutes, usedMs, packageName = packageName.trim())
    }

    /**
     * One category's usage on the evaluated day against its configured category limit.
     *
     * [category] is the existing [AppCategory]; the evaluator classifies nothing itself, so
     * a package's category is decided in exactly one place ([AppCategories]).
     */
    suspend fun evaluateCategory(
        accountId: Long,
        childId: Long,
        dateKey: String,
        category: AppCategory,
    ): ScreenTimeLimitEvaluation {
        requireValidTarget(accountId, childId, dateKey)
        val stored = limitRepository.limit(accountId, childId, LimitScope.CATEGORY, category)
        val usedMs = usageRepository.getCategoryUsageMs(accountId, childId, dateKey, category)
        return evaluation(LimitScope.CATEGORY, stored?.limitMinutes, usedMs, category = category)
    }

    /**
     * Evaluates several scopes for one child and day, returning **one result per requested
     * scope** with no precedence between them (§28): an exceeded category does not remove
     * the total's result, and vice versa. A caller that wants "is anything exceeded?" asks
     * that question itself; deciding *what to do about it* is enforcement and lives
     * elsewhere.
     *
     * @param packages packages to evaluate as [LimitScope.APP]; the app's category is
     *   resolved through [AppCategories] so its [LimitScope.CATEGORY] can be included
     *   without the caller having to classify anything.
     * @param total whether to include the [LimitScope.TOTAL] result.
     * @param includeCategories whether to include [LimitScope.CATEGORY] results for the
     *   categories of the evaluated packages.
     */
    suspend fun evaluateAll(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packages: Set<String> = emptySet(),
        total: Boolean = true,
        includeCategories: Boolean = true,
    ): List<ScreenTimeLimitEvaluation> {
        requireValidTarget(accountId, childId, dateKey)

        val results = mutableListOf<ScreenTimeLimitEvaluation>()
        if (total) results += evaluateTotal(accountId, childId, dateKey)

        val normalizedPackages = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (packageName in normalizedPackages) {
            results += evaluateApp(accountId, childId, dateKey, packageName)
        }

        if (includeCategories) {
            // Derived from the packages themselves, so the category view can never disagree
            // with the app view about which category an app belongs to.
            val categories = normalizedPackages.map { AppCategories.categoryFor(it) }.distinct().sorted()
            for (category in categories) {
                results += evaluateCategory(accountId, childId, dateKey, category)
            }
        }
        return results
    }

    private fun requireValidTarget(accountId: Long, childId: Long, dateKey: String) {
        require(accountId > 0L) { "accountId must be positive, was $accountId" }
        require(childId > 0L) { "childId must be positive, was $childId" }
        require(UsageDateKey.isValid(dateKey)) {
            "dateKey must be canonical yyyy-MM-dd, was '$dateKey'"
        }
    }

    /**
     * Builds the result for one scope.
     *
     * A stored limit is classified with the project's existing validity predicate
     * ([ScreenTimeLimits.isAccepted]) rather than its normalizer: `normalize` silently turns
     * a negative limit into "unlimited" and clamps an oversized one, and either would hide a
     * corrupt configuration. So a value outside `0..1440` is reported as invalid and
     * evaluated no further.
     */
    private fun evaluation(
        scope: LimitScope,
        limitMinutes: Int?,
        usedMs: Long,
        packageName: String? = null,
        category: AppCategory? = null,
    ): ScreenTimeLimitEvaluation {
        if (limitMinutes != null && !ScreenTimeLimits.isAccepted(limitMinutes)) {
            return ScreenTimeLimitEvaluation(
                scope = scope,
                // Usage is still reported, so a caller sees how much was used despite the
                // broken limit; the state is "no valid limit" rather than a fake verdict.
                status = limitStatus(null, usedMs),
                packageName = packageName,
                category = category,
                invalidLimitMinutes = limitMinutes,
            )
        }
        return ScreenTimeLimitEvaluation(
            scope = scope,
            status = limitStatus(limitMinutes, usedMs),
            packageName = packageName,
            category = category,
        )
    }
}
