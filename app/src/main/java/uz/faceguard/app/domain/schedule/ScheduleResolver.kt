package uz.faceguard.app.domain.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Phase 5 Step 1: which schedule (if any) is active at a given instant.
 *
 * The resolver is pure and deterministic. It never reads a clock, never touches the
 * system default timezone and never mutates anything: the caller supplies the time,
 * and — when resolving an [Instant] — the [ZoneId] to interpret it in.
 *
 * It answers a *temporal* question only. It does not look at the foreground app, the
 * ProtectionEngine, screen-time usage or the protected-app catalogue, and it returns
 * no decision: enforcement and policy precedence are later steps.
 */
sealed interface ScheduleResolution {

    /** Nothing is active at the resolved time. Returned explicitly, never as a null. */
    data object NoActiveSchedule : ScheduleResolution

    /** Exactly one schedule is active, or one wins on a unique highest priority. */
    data class ActiveSchedule(val schedule: ScheduleRule) : ScheduleResolution

    /**
     * More than one schedule shares the highest active priority.
     *
     * A tie is surfaced, never resolved: schedules are equally authoritative, and picking
     * one (by id, name, mode or input order) would silently discard the others' actions.
     * [schedules] contains *every* tied schedule, ordered by `(name, id)` for stable
     * presentation only — that order is **not** a preference between them.
     */
    data class ScheduleConflict(val schedules: List<ScheduleRule>) : ScheduleResolution
}

object ScheduleResolver {

    /**
     * Resolves the schedules for [dateTime] in that date-time's own calendar terms.
     *
     * @param schedules the candidate schedules; disabled ones are ignored.
     */
    fun resolve(schedules: List<ScheduleRule>, dateTime: LocalDateTime): ScheduleResolution =
        resolveActive(schedules.filter { isActive(it, dateTime) })

    /**
     * Resolves the schedules for [instant] interpreted in [zone].
     *
     * Timezone is explicit: the same instant can be in a different local day, and so
     * select a different schedule, depending on [zone]. Reading the system default here
     * would make the result depend on hidden device state.
     */
    fun resolve(schedules: List<ScheduleRule>, instant: Instant, zone: ZoneId): ScheduleResolution =
        resolve(schedules, instant.atZone(zone).toLocalDateTime())

    /**
     * True when [rule] is enabled and [dateTime]'s weekday and minute-of-day fall inside
     * its window.
     *
     * The owning-day rule: the weekday that must be selected is the day the window
     * *starts* on. For a same-day window that is simply [dateTime]'s weekday. For a
     * cross-midnight window, a time before the window's end belongs to the previous
     * day's interval — so at `Tuesday 00:30` it is *Monday* that must be selected, and
     * Tuesday need not be selected at all.
     */
    fun isActive(rule: ScheduleRule, dateTime: LocalDateTime): Boolean {
        if (!rule.enabled) return false

        val minuteOfDay = dateTime.hour * ScheduleWindow.MINUTES_PER_HOUR + dateTime.minute
        if (!rule.window.containsMinute(minuteOfDay)) return false

        val owningDay = owningDay(rule.window, dateTime.toLocalDate(), minuteOfDay)
        return rule.days.contains(owningDay)
    }

    private fun resolveActive(active: List<ScheduleRule>): ScheduleResolution {
        if (active.isEmpty()) return ScheduleResolution.NoActiveSchedule

        val highestPriority = active.maxOf { it.priority }
        val winners = active.filter { it.priority == highestPriority }

        return when (winners.size) {
            1 -> ScheduleResolution.ActiveSchedule(winners.single())
            else -> ScheduleResolution.ScheduleConflict(winners.sortedWith(compareBy({ it.name }, { it.id })))
        }
    }

    private fun owningDay(window: ScheduleWindow, date: LocalDate, minuteOfDay: Int): DayOfWeek {
        // A cross-midnight window's early-morning portion belongs to the previous day.
        val belongsToPreviousDay = window.crossesMidnight && minuteOfDay < window.endMinuteOfDay
        return (if (belongsToPreviousDay) date.minusDays(1) else date).dayOfWeek
    }
}
