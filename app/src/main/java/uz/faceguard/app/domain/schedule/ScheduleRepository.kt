package uz.faceguard.app.domain.schedule

import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 5 Step 2: a schedule that has not been persisted yet.
 *
 * [ScheduleRule] requires a positive id, because an id is what makes a schedule addressable
 * and a schedule with no id is not a schedule. A brand-new schedule therefore cannot be
 * expressed as a [ScheduleRule] — it has no id — so creation takes this draft and returns the
 * stored [ScheduleRule] with the id persistence generated.
 *
 * The draft carries exactly the fields a [ScheduleRule] carries except the id, and
 * [withId] builds the rule through the Step 1 constructor, so the domain's own validation
 * (non-blank name, implemented action, valid window and days) is the single place invalid
 * input is rejected.
 */
data class ScheduleDraft(
    val name: String,
    val mode: ScheduleMode,
    val window: ScheduleWindow,
    val days: ScheduleDays,
    val action: ProtectionAction,
    val priority: Int = 0,
    val enabled: Boolean = true,
) {

    /**
     * The [ScheduleRule] this draft describes, once persistence has assigned [id].
     *
     * @throws IllegalArgumentException when the draft is not a valid schedule; the check is
     *   [ScheduleRule]'s own, not a second copy of it.
     */
    fun withId(id: Long): ScheduleRule = ScheduleRule(
        id = id,
        name = name,
        mode = mode,
        window = window,
        days = days,
        action = action,
        priority = priority,
        enabled = enabled,
    )
}

/**
 * Phase 5 Step 2: stores and retrieves per-child schedules.
 *
 * Exactly one source of truth: schedules live in `schedule_rules` and their affected apps in
 * the normalized `schedule_app_targets` relation. Nothing here duplicates
 * `ChildAppPolicy` (ALLOW/LIMIT/BLOCK, daily limit, action) — a schedule's affected apps are
 * membership only, and normal app policy stays authoritative for normal policy configuration.
 *
 * Every method is **account + child scoped**. A schedule id is never a sufficient boundary on
 * its own, so a read or write for the wrong account or child behaves as "not found" rather
 * than returning or mutating a sibling's schedule. There is no fallback to another child.
 *
 * This is persistence only. It never reads a clock, never decides which schedule is active,
 * never resolves conflicts and never touches the policy engine or `ProtectionActionExecutor`;
 * temporal resolution stays in [ScheduleResolver] and enforcement is a later step.
 */
interface ScheduleRepository {

    /** All schedules of one child, ordered deterministically by creation. */
    fun observeSchedules(accountId: Long, childId: Long): Flow<List<ScheduleRule>>

    /** [observeSchedules] as a one-shot read. */
    suspend fun schedules(accountId: Long, childId: Long): List<ScheduleRule>

    /** One schedule, or `null` when it is not this account + child's. */
    suspend fun schedule(accountId: Long, childId: Long, scheduleId: Long): ScheduleRule?

    /**
     * Stores a new schedule and its affected apps atomically, and returns the stored schedule
     * with the positive id persistence generated.
     *
     * @throws IllegalArgumentException when the draft is not a valid schedule, or a target
     *   package name is blank.
     */
    suspend fun create(
        accountId: Long,
        childId: Long,
        draft: ScheduleDraft,
        targetPackages: Set<String> = emptySet(),
    ): ScheduleRule

    /**
     * Replaces an existing schedule's fields and its complete affected-app set atomically.
     *
     * @return the stored schedule, or `null` when no schedule with that id belongs to this
     *   account + child (nothing is written and another child's schedule is never touched).
     */
    suspend fun update(
        accountId: Long,
        childId: Long,
        schedule: ScheduleRule,
        targetPackages: Set<String>,
    ): ScheduleRule?

    /** Deletes one schedule and its affected-app rows; a no-op when it is not this child's. */
    suspend fun delete(accountId: Long, childId: Long, scheduleId: Long)

    /** Deletes every schedule of one child together with their affected-app rows. */
    suspend fun deleteAllForChild(accountId: Long, childId: Long)

    /** The packages one schedule targets, ordered deterministically. */
    fun observeTargetPackages(accountId: Long, childId: Long, scheduleId: Long): Flow<List<String>>

    /** [observeTargetPackages] as a one-shot read. */
    suspend fun targetPackages(accountId: Long, childId: Long, scheduleId: Long): List<String>

    /**
     * Replaces one schedule's complete affected-app set atomically.
     *
     * A schedule that is not this account + child's is left alone (no orphan rows are
     * created). An empty set is a valid configuration meaning "targets no apps".
     *
     * @throws IllegalArgumentException when a package name is blank.
     */
    suspend fun replaceTargetPackages(
        accountId: Long,
        childId: Long,
        scheduleId: Long,
        packageNames: Set<String>,
    )
}
