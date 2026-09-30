package uz.faceguard.app.domain.schedule

import java.time.DayOfWeek
import java.time.LocalTime
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 5 Step 1: the Schedule domain foundation (pure, no Android, no I/O).
 *
 * A schedule is a named, per-child description of "when should this action apply".
 * Storage, enforcement and UI are later steps; this file only models a schedule and
 * the primitives it is built from, so temporal resolution is deterministic and
 * trivially unit-testable.
 *
 * [ScheduleMode] is descriptive metadata only — it never implies behaviour. The
 * action a schedule applies is carried explicitly in [ScheduleRule.action].
 */

// ---------------------------------------------------------------------------
// Mode
// ---------------------------------------------------------------------------

/** What a schedule is *for*. Descriptive metadata; no behaviour is attached. */
enum class ScheduleMode {
    NORMAL,
    STUDY,
    SLEEP,
    SCHOOL,
    CUSTOM,
}

// ---------------------------------------------------------------------------
// Days
// ---------------------------------------------------------------------------

/**
 * The selected weekdays, as a bit mask.
 *
 * Monday is the lowest bit and Sunday the highest:
 *
 * ```
 * Monday  0b0000001     Thursday 0b0001000     Sunday 0b1000000
 * Tuesday 0b0000010     Friday   0b0010000
 * Wednesday 0b0000100   Saturday 0b0100000
 * ```
 *
 * At least one day must be selected; an empty mask is rejected at construction, so
 * a schedule can never silently fire on no days at all.
 */
data class ScheduleDays(val mask: Int) {

    init {
        require(mask and ALL_MASK == mask) {
            "mask 0x${mask.toString(16)} contains bits outside Mon..Sun (0x${ALL_MASK.toString(16)})"
        }
        require(mask != 0) { "at least one day must be selected" }
    }

    /** The selected days, in Monday-to-Sunday order. */
    val days: List<DayOfWeek> get() = DayOfWeek.entries.filter { contains(it) }

    /** True when every day of the week is selected. */
    val isEveryDay: Boolean get() = mask == ALL_MASK

    fun contains(day: DayOfWeek): Boolean = (mask and bit(day)) != 0

    override fun toString(): String = "ScheduleDays(${days.joinToString(",") { it.name.take(3) }})"

    companion object {
        /** Monday = bit 0 … Sunday = bit 6, so all seven set are the low seven bits. */
        const val ALL_MASK: Int = 0b1111111

        val ALL: ScheduleDays = ScheduleDays(ALL_MASK)

        /** Monday–Friday (bits 0..4). */
        val WEEKDAYS: ScheduleDays = ScheduleDays(0b0011111)

        /** Saturday + Sunday (bits 5..6). */
        val WEEKENDS: ScheduleDays = ScheduleDays(0b1100000)

        private fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

        fun of(vararg days: DayOfWeek): ScheduleDays = of(days.toSet())

        /** @throws IllegalArgumentException when [days] is empty. */
        fun of(days: Set<DayOfWeek>): ScheduleDays =
            ScheduleDays(days.fold(0) { acc, day -> acc or bit(day) })
    }
}

// ---------------------------------------------------------------------------
// Window
// ---------------------------------------------------------------------------

/**
 * A time-of-day window with whole-minute resolution and `[start, end)` semantics:
 * the start instant is inside the window and the end instant is not.
 *
 * Two shapes are valid:
 *
 * - `end > start` — a same-day interval, e.g. `09:00–17:00`.
 * - `end < start` — the window crosses midnight, e.g. `22:00–07:00`. A cross-midnight
 *   window belongs to the day it starts on (its *owning* day); see [ScheduleRule].
 *
 * `start == end` is not a "zero-length" window and not a "full day" — it is ambiguous,
 * so it is rejected. Seconds and nanoseconds are rejected too: the model is whole-minute
 * by construction, so a caller cannot silently lose precision via `07:30:30`.
 */
data class ScheduleWindow(val start: LocalTime, val end: LocalTime) {

    init {
        require(start.second == 0 && start.nano == 0) { "start must be a whole minute, was $start" }
        require(end.second == 0 && end.nano == 0) { "end must be a whole minute, was $end" }
        require(start != end) { "start and end must differ (start == end is ambiguous)" }
    }

    /** Minutes from midnight, `0..1439`. The single canonical encoding of [start]. */
    val startMinuteOfDay: Int get() = start.hour * MINUTES_PER_HOUR + start.minute

    /** Minutes from midnight, `0..1439`. The single canonical encoding of [end]. */
    val endMinuteOfDay: Int get() = end.hour * MINUTES_PER_HOUR + end.minute

    /** True when the window runs past midnight, so its end falls on the following day. */
    val crossesMidnight: Boolean get() = endMinuteOfDay < startMinuteOfDay

    /**
     * True when minute-of-day [minuteOfDay] (`0..1439`) is inside `[start, end)`.
     *
     * This is a *within-a-day* question only; which calendar day owns the window is a
     * question for [ScheduleRule] and its [ScheduleDays].
     */
    fun containsMinute(minuteOfDay: Int): Boolean {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) {
            "minuteOfDay must be in 0..1439, was $minuteOfDay"
        }
        return if (crossesMidnight) {
            minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        } else {
            minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay
        }
    }

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

        /**
         * Builds a window from two minute-of-day values, each `0..1439`.
         *
         * `1440` (a notional `24:00`) is deliberately rejected rather than normalised to
         * `0`: `00:00–24:00` is a full day, not the zero-length `00:00–00:00`, and quietly
         * folding the two together would turn the first into the second's invalid shape.
         * A caller that means "the whole day" should select every day in [ScheduleDays]
         * instead.
         */
        fun ofMinutes(startMinuteOfDay: Int, endMinuteOfDay: Int): ScheduleWindow {
            require(startMinuteOfDay in 0 until MINUTES_PER_DAY) {
                "startMinuteOfDay must be in 0..1439, was $startMinuteOfDay"
            }
            require(endMinuteOfDay in 0 until MINUTES_PER_DAY) {
                "endMinuteOfDay must be in 0..1439, was $endMinuteOfDay"
            }
            return ScheduleWindow(ofMinuteOfDay(startMinuteOfDay), ofMinuteOfDay(endMinuteOfDay))
        }

        internal fun ofMinuteOfDay(minuteOfDay: Int): LocalTime =
            LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
    }
}

// ---------------------------------------------------------------------------
// Rule
// ---------------------------------------------------------------------------

/**
 * One schedule: a named, day-scoped time window that applies [action] when active.
 *
 * [action] reuses the project's existing [ProtectionAction] and is validated against the
 * canonical [IMPLEMENTED_ACTIONS] set, so a schedule can never carry a domain-only action
 * (`DIM`, `BLUR`, `BLACK_SCREEN`) that this build cannot enforce.
 *
 * The owning-day rule for a cross-midnight window is defined on [ScheduleResolver]: the
 * day a window *starts* on is the day that must be selected, and its after-midnight
 * continuation stays active even though that continuation's weekday is not selected.
 */
data class ScheduleRule(
    val id: Long,
    val name: String,
    val mode: ScheduleMode,
    val window: ScheduleWindow,
    val days: ScheduleDays,
    val action: ProtectionAction,
    val priority: Int = 0,
    val enabled: Boolean = true,
) {

    init {
        require(id > 0L) { "id must be positive, was $id" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(action in IMPLEMENTED_ACTIONS) {
            "action $action is not implemented (see IMPLEMENTED_ACTIONS)"
        }
    }
}
