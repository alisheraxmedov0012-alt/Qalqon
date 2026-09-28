package uz.faceguard.app.domain.schedule

import java.time.Instant
import java.time.ZoneId

/**
 * Phase 5 Step 4: the schedule state that applies to [packageName] for one child, resolved at
 * [now] in [zone].
 *
 * This is the single place the runtime's schedule lookup is expressed, so the policy integration
 * consumes one well-defined result instead of assembling it inline. It is a *filter plus the
 * existing resolver*, not a second resolver: it decides which schedules may participate at all,
 * then delegates the temporal decision to [ScheduleResolver] unchanged.
 *
 * It applies the two gates the approved contract requires:
 *  - **affected apps** — only schedules whose membership ([targets]) contains [packageName]
 *    participate, so a schedule never applies to an app the parent did not select, and a schedule
 *    with no targets affects nothing;
 *  - **scope** — [schedules] and [targets] are those of a single child. This function cannot widen
 *    its own input, and the runtime keys both maps by child, so one child's (or one account's)
 *    schedules can never be resolved for another.
 *
 * When nothing targets the package, or nothing targeting it is active, the result is
 * [ScheduleResolution.NoActiveSchedule]. Timezone is explicit, so the same instant can select a
 * different schedule in a different zone.
 *
 * @param targets schedule id -> the package names that schedule targets.
 */
fun resolveScheduleForPackage(
    schedules: List<ScheduleRule>,
    targets: Map<Long, Set<String>>,
    packageName: String,
    now: Instant,
    zone: ZoneId,
): ScheduleResolution {
    val applicable = schedules.filter { targets[it.id]?.contains(packageName) == true }
    if (applicable.isEmpty()) return ScheduleResolution.NoActiveSchedule
    return ScheduleResolver.resolve(applicable, now, zone)
}
