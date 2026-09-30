package uz.faceguard.app.data.repository

import uz.faceguard.app.data.db.ScheduleRuleEntity
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleDraft
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow

/**
 * Phase 5 Step 2: Room entity <-> domain mapping for schedules.
 *
 * Kept out of the repository class so the mapping is unit-testable on the JVM (no database
 * needed), the same way `ChildAppPolicyMapper` is.
 *
 * Unlike the app-policy mapper, this mapping does **not** fall back to a safe default when
 * stored data cannot be parsed. A schedule whose stored `mode`/`action`/window/days are not
 * the values the domain can represent is corrupt, and silently substituting an action would
 * change what the parent configured — so the enum lookups throw and the Step 1 constructors
 * reject the rest. The failure surfaces at the read, loudly, instead of enforcing something
 * the parent never asked for.
 */

/** Room row -> domain schedule. Throws when the stored row is not a representable schedule. */
internal fun ScheduleRuleEntity.toDomainSchedule(): ScheduleRule = ScheduleRule(
    id = id,
    name = name,
    mode = ScheduleMode.valueOf(mode),
    window = ScheduleWindow.ofMinutes(startMinuteOfDay, endMinuteOfDay),
    days = ScheduleDays(daysMask),
    action = ProtectionAction.valueOf(action),
    priority = priority,
    enabled = enabled,
)

/**
 * New draft -> Room row. [id] is `0` for an insert (Room then generates one) and the existing
 * id for an update; the remaining fields are the draft's own.
 */
internal fun ScheduleDraft.toEntity(
    accountId: Long,
    childId: Long,
    id: Long,
    createdAt: Long,
    updatedAt: Long,
): ScheduleRuleEntity = ScheduleRuleEntity(
    id = id,
    accountId = accountId,
    childId = childId,
    name = name,
    mode = mode.name,
    enabled = enabled,
    startMinuteOfDay = window.startMinuteOfDay,
    endMinuteOfDay = window.endMinuteOfDay,
    daysMask = days.mask,
    priority = priority,
    action = action.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Existing domain schedule -> Room row for an update. `createdAt` is carried over unchanged so
 * an edit cannot rewrite when the schedule was created; only [updatedAt] moves.
 */
internal fun ScheduleRule.toEntity(
    accountId: Long,
    childId: Long,
    createdAt: Long,
    updatedAt: Long,
): ScheduleRuleEntity = ScheduleRuleEntity(
    id = id,
    accountId = accountId,
    childId = childId,
    name = name,
    mode = mode.name,
    enabled = enabled,
    startMinuteOfDay = window.startMinuteOfDay,
    endMinuteOfDay = window.endMinuteOfDay,
    daysMask = days.mask,
    priority = priority,
    action = action.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
