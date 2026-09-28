package uz.faceguard.app.feature.schedule

import java.time.DayOfWeek
import uz.faceguard.app.R
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleDraft
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow

/**
 * Phase 5 Step 5: the result of validating an editor state.
 *
 * A sealed result rather than a boolean or a thrown exception, so the UI can show a specific
 * localized message and tests can assert the exact reason. Mirrors the project's existing
 * `RequestValidation`.
 */
sealed interface ScheduleEditorValidation {
    data object Valid : ScheduleEditorValidation
    data class Invalid(val messageRes: Int) : ScheduleEditorValidation
}

/**
 * Phase 5 Step 5: the schedule editor's state — a *UI state wrapper*, not a second schedule model.
 *
 * Every field is what the parent is currently editing, held here until Save. The persisted form is
 * still produced by the existing domain types ([ScheduleDraft], [ScheduleDays], [ScheduleWindow],
 * [ScheduleRule]); nothing about them is redefined.
 *
 * Times are held as whole minute-of-day values, which is exactly the domain's own representation
 * ([ScheduleWindow.startMinuteOfDay]) and has no seconds by construction. Days are a
 * `Set<DayOfWeek>`, converted to the persisted [ScheduleDays] mask only when a draft is built, so
 * an empty selection can be *represented* while editing (and reported as invalid) instead of
 * throwing during typing.
 *
 * [name] and [priorityText] are raw text so the fields can hold in-progress input; they are
 * parsed/trimmed when the draft is built.
 */
data class ScheduleEditorState(
    /** `null` while creating; the existing id while editing. */
    val scheduleId: Long? = null,
    val name: String = "",
    val mode: ScheduleMode = ScheduleMode.NORMAL,
    val action: ProtectionAction = ProtectionAction.HARD_BLOCK,
    val days: Set<DayOfWeek> = ScheduleDays.WEEKDAYS.days.toSet(),
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_END_MINUTE,
    val priorityText: String = "0",
    val enabled: Boolean = true,
    /** The affected apps: only packages already in the parent's protected-app catalog. */
    val selectedPackages: Set<String> = emptySet(),
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val errorMessageRes: Int? = null,
) {

    val isNew: Boolean get() = scheduleId == null

    /** True while a write is in flight, so the UI can prevent duplicate submissions. */
    val busy: Boolean get() = saving || deleting

    /** The trimmed user-visible name; blank when it is empty or whitespace only. */
    val trimmedName: String get() = name.trim()

    val priority: Int? get() = priorityText.trim().toIntOrNull()

    /**
     * Validates the current input. The domain remains the final authority, so this mirrors the
     * domain's own rules (non-blank name, at least one day, `start != end`) rather than inventing
     * different ones — a draft built from a valid state is still validated again by
     * [ScheduleRule]'s constructor.
     */
    fun validate(): ScheduleEditorValidation = when {
        trimmedName.isBlank() -> ScheduleEditorValidation.Invalid(R.string.schedule_error_name)

        days.isEmpty() -> ScheduleEditorValidation.Invalid(R.string.schedule_error_days)

        startMinuteOfDay !in MINUTE_OF_DAY_RANGE ->
            ScheduleEditorValidation.Invalid(R.string.schedule_error_time)

        endMinuteOfDay !in MINUTE_OF_DAY_RANGE ->
            ScheduleEditorValidation.Invalid(R.string.schedule_error_time)

        // [start, end) semantics: equal bounds are ambiguous, not a full day. end < start is a
        // valid cross-midnight window and is deliberately accepted.
        startMinuteOfDay == endMinuteOfDay ->
            ScheduleEditorValidation.Invalid(R.string.schedule_error_time)

        priority == null -> ScheduleEditorValidation.Invalid(R.string.schedule_error_priority)

        else -> ScheduleEditorValidation.Valid
    }

    /** True when the window runs past midnight (`end < start`) — valid, and shown as such. */
    val crossesMidnight: Boolean get() = endMinuteOfDay < startMinuteOfDay

    /**
     * The draft to persist, or `null` when the input is invalid. Built through the existing domain
     * types, so the window's `[start, end)` rule, the day mask and the implemented-action rule are
     * the domain's, not a copy of them.
     */
    fun toDraft(): ScheduleDraft? {
        if (validate() !is ScheduleEditorValidation.Valid) return null
        val priorityValue = priority ?: return null
        return ScheduleDraft(
            name = trimmedName,
            mode = mode,
            window = ScheduleWindow.ofMinutes(startMinuteOfDay, endMinuteOfDay),
            days = ScheduleDays.of(days),
            action = action,
            priority = priorityValue,
            enabled = enabled,
        )
    }

    /** The affected apps to persist. Zero selected means "targets no apps", never "all apps". */
    fun targetPackages(): Set<String> = selectedPackages

    /** True when [packageName] is currently selected. */
    fun isSelected(packageName: String): Boolean = packageName in selectedPackages

    fun withDayToggled(day: DayOfWeek): ScheduleEditorState =
        copy(days = if (day in days) days - day else days + day)

    companion object {
        /** 08:00 — a sensible same-day default; the parent changes either bound freely. */
        const val DEFAULT_START_MINUTE = 8 * 60

        /** 15:00 — after [DEFAULT_START_MINUTE], so a new schedule is valid without edits. */
        const val DEFAULT_END_MINUTE = 15 * 60

        /** The domain's whole-minute domain: 0..1439. `24:00` is not a representable bound. */
        val MINUTE_OF_DAY_RANGE = 0 until ScheduleWindow.MINUTES_PER_DAY

        /** A brand-new schedule: the domain defaults, with no apps targeted yet. */
        fun create() = ScheduleEditorState()

        /** Loads an existing schedule and its affected apps into the editor, preserving its id. */
        fun from(rule: ScheduleRule, targetPackages: Set<String>) = ScheduleEditorState(
            scheduleId = rule.id,
            name = rule.name,
            mode = rule.mode,
            action = rule.action,
            days = rule.days.days.toSet(),
            startMinuteOfDay = rule.window.startMinuteOfDay,
            endMinuteOfDay = rule.window.endMinuteOfDay,
            priorityText = rule.priority.toString(),
            enabled = rule.enabled,
            selectedPackages = targetPackages,
        )
    }
}
