package uz.faceguard.app.feature.schedule

import java.time.DayOfWeek
import uz.faceguard.app.R
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRule

/**
 * Phase 5 Step 5: the schedule UI's presentation mappings.
 *
 * All of these are pure functions returning string-resource ids (or plain formatted values), so
 * the visible text always comes from `strings.xml` and never from a hardcoded literal in a
 * composable — and so the mapping itself is JVM-testable without a device.
 *
 * The action list is derived from the project's canonical implemented-action set rather than a
 * second list: `DIM`, `BLUR` and `BLACK_SCREEN` exist in the domain but are not schedule actions,
 * so they can never be offered.
 */

/**
 * The actions a schedule may use, in the approved order: `ALLOW`, `WARNING`, `SOFT_BLOCK`,
 * `HARD_BLOCK`, `MUTE`. Derived from [uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS] so it
 * cannot drift from what the build can actually enforce.
 */
val SCHEDULE_ACTIONS: List<ProtectionAction> =
    ProtectionAction.entries.filter { it in uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS }

/** Every day, Monday first (the order [uz.faceguard.app.domain.schedule.ScheduleDays.days] uses). */
val SCHEDULE_DAYS_ORDER: List<DayOfWeek> = DayOfWeek.entries.toList()

fun dayLabelRes(day: DayOfWeek): Int = when (day) {
    DayOfWeek.MONDAY -> R.string.schedule_day_monday
    DayOfWeek.TUESDAY -> R.string.schedule_day_tuesday
    DayOfWeek.WEDNESDAY -> R.string.schedule_day_wednesday
    DayOfWeek.THURSDAY -> R.string.schedule_day_thursday
    DayOfWeek.FRIDAY -> R.string.schedule_day_friday
    DayOfWeek.SATURDAY -> R.string.schedule_day_saturday
    DayOfWeek.SUNDAY -> R.string.schedule_day_sunday
}

fun modeLabelRes(mode: ScheduleMode): Int = when (mode) {
    ScheduleMode.NORMAL -> R.string.schedule_mode_normal
    ScheduleMode.STUDY -> R.string.schedule_mode_study
    ScheduleMode.SLEEP -> R.string.schedule_mode_sleep
    ScheduleMode.SCHOOL -> R.string.schedule_mode_school
    ScheduleMode.CUSTOM -> R.string.schedule_mode_custom
}

/**
 * What a mode means to the parent. Purely descriptive: a mode is metadata and never selects an
 * action, so these labels must not promise enforcement (see [modeLabelRes] and, for the action
 * that actually applies, [actionDescriptionRes]).
 */
fun modeDescriptionRes(mode: ScheduleMode): Int = when (mode) {
    ScheduleMode.NORMAL -> R.string.schedule_mode_normal_hint
    ScheduleMode.STUDY -> R.string.schedule_mode_study_hint
    ScheduleMode.SLEEP -> R.string.schedule_mode_sleep_hint
    ScheduleMode.SCHOOL -> R.string.schedule_mode_school_hint
    ScheduleMode.CUSTOM -> R.string.schedule_mode_custom_hint
}

fun actionLabelRes(action: ProtectionAction): Int = when (action) {
    ProtectionAction.ALLOW -> R.string.schedule_action_allow
    ProtectionAction.WARNING -> R.string.schedule_action_warning
    ProtectionAction.SOFT_BLOCK -> R.string.schedule_action_soft_block
    ProtectionAction.HARD_BLOCK -> R.string.schedule_action_hard_block
    ProtectionAction.MUTE -> R.string.schedule_action_mute
    // Not selectable in this build; the screen never offers them, but a total mapping keeps the
    // function honest rather than falling through to a wrong label.
    ProtectionAction.DIM -> R.string.schedule_action_dim
    ProtectionAction.BLUR -> R.string.schedule_action_blur
    ProtectionAction.BLACK_SCREEN -> R.string.schedule_action_black_screen
}

/**
 * What an action actually does, stated in terms of the shipped executor: `SOFT_BLOCK` shows the
 * blocking overlay, `HARD_BLOCK` shows the overlay and mutes, `MUTE` only mutes, `WARNING` asks
 * the UI to warn, and `ALLOW` restricts nothing.
 */
fun actionDescriptionRes(action: ProtectionAction): Int = when (action) {
    ProtectionAction.ALLOW -> R.string.schedule_action_allow_hint
    ProtectionAction.WARNING -> R.string.schedule_action_warning_hint
    ProtectionAction.SOFT_BLOCK -> R.string.schedule_action_soft_block_hint
    ProtectionAction.HARD_BLOCK -> R.string.schedule_action_hard_block_hint
    ProtectionAction.MUTE -> R.string.schedule_action_mute_hint
    ProtectionAction.DIM -> R.string.schedule_action_dim_hint
    ProtectionAction.BLUR -> R.string.schedule_action_blur_hint
    ProtectionAction.BLACK_SCREEN -> R.string.schedule_action_black_screen_hint
}

/**
 * A whole minute-of-day as `HH:mm` (`0..1439`). Zero-padded and locale-independent, and there are
 * no seconds to render because the domain stores none.
 */
fun formatMinuteOfDay(minuteOfDay: Int): String {
    val normalized = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    val hour = normalized / 60
    val minute = normalized % 60
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

/** `HH:mm–HH:mm`, with the cross-midnight flag when the window runs past midnight. */
fun formatWindow(rule: ScheduleRule): String =
    "${formatMinuteOfDay(rule.window.startMinuteOfDay)}–${formatMinuteOfDay(rule.window.endMinuteOfDay)}"

/** The nine-day-set presets offered in the editor, as day sets. */
object DayPresets {
    val ALL: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    val WEEKDAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    val WEEKENDS: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
