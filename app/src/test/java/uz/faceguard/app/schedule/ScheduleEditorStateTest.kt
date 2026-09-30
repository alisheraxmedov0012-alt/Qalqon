package uz.faceguard.app.schedule

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow
import uz.faceguard.app.feature.schedule.DayPresets
import uz.faceguard.app.feature.schedule.SCHEDULE_ACTIONS
import uz.faceguard.app.feature.schedule.ScheduleEditorState
import uz.faceguard.app.feature.schedule.ScheduleEditorValidation
import uz.faceguard.app.feature.schedule.actionDescriptionRes
import uz.faceguard.app.feature.schedule.actionLabelRes
import uz.faceguard.app.feature.schedule.dayLabelRes
import uz.faceguard.app.feature.schedule.formatMinuteOfDay
import uz.faceguard.app.feature.schedule.modeDescriptionRes
import uz.faceguard.app.feature.schedule.modeLabelRes

/**
 * Phase 5 Step 5 (pure JVM): the schedule editor state, its validation and its mapping to the
 * existing domain types, plus the presentation labels. No Android, no database, no UI.
 */
class ScheduleEditorStateTest {

    private fun rule(
        id: Long = 7L,
        name: String = "Bedtime",
        mode: ScheduleMode = ScheduleMode.SLEEP,
        start: Int = 1320,
        end: Int = 420,
        days: ScheduleDays = ScheduleDays.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        action: ProtectionAction = ProtectionAction.MUTE,
        priority: Int = 5,
        enabled: Boolean = false,
    ) = ScheduleRule(
        id = id,
        name = name,
        mode = mode,
        window = ScheduleWindow.ofMinutes(start, end),
        days = days,
        action = action,
        priority = priority,
        enabled = enabled,
    )

    // ---- 1. new editor default state ---------------------------------------

    @Test
    fun test1_newEditorHasSensibleDefaultsAndNoId() {
        val state = ScheduleEditorState.create()

        assertTrue(state.isNew)
        assertNull(state.scheduleId)
        assertEquals("", state.name)
        assertEquals(ScheduleMode.NORMAL, state.mode)
        assertEquals(ProtectionAction.HARD_BLOCK, state.action)
        assertEquals(ScheduleDays.WEEKDAYS.days.toSet(), state.days)
        assertEquals(0, state.priority)
        assertTrue(state.enabled)
        assertTrue("nothing targeted by default", state.selectedPackages.isEmpty())
        // A brand-new schedule has no name yet, so it is not saveable until the parent types one.
        assertEquals(ScheduleEditorValidation.Invalid(R.string.schedule_error_name), state.validate())
        assertFalse(state.copy(name = "Bedtime").validate() is ScheduleEditorValidation.Invalid)
    }

    // ---- 2/3. loading an existing schedule and its apps --------------------

    @Test
    fun test2_editorLoadsAnExistingScheduleUnchanged() {
        val state = ScheduleEditorState.from(rule(), setOf("com.a", "com.b"))

        assertFalse(state.isNew)
        assertEquals(7L, state.scheduleId)
        assertEquals("Bedtime", state.name)
        assertEquals(ScheduleMode.SLEEP, state.mode)
        assertEquals(ProtectionAction.MUTE, state.action)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), state.days)
        assertEquals(1320, state.startMinuteOfDay)
        assertEquals(420, state.endMinuteOfDay)
        assertEquals("5", state.priorityText)
        assertFalse(state.enabled)
        assertTrue("a cross-midnight window survives loading", state.crossesMidnight)
    }

    @Test
    fun test3_targetPackagesLoadIntoTheSelection() {
        val state = ScheduleEditorState.from(rule(), setOf("com.a", "com.b"))

        assertEquals(setOf("com.a", "com.b"), state.selectedPackages)
        assertEquals(setOf("com.a", "com.b"), state.targetPackages())
        assertTrue(state.isSelected("com.a"))
        assertFalse(state.isSelected("com.c"))
    }

    // ---- 4/5. draft construction -------------------------------------------

    @Test
    fun test4_draftIsBuiltFromTheExistingDomainTypes() {
        val state = ScheduleEditorState.create().copy(
            name = "Homework",
            mode = ScheduleMode.STUDY,
            action = ProtectionAction.SOFT_BLOCK,
            days = setOf(DayOfWeek.SATURDAY),
            startMinuteOfDay = 600,
            endMinuteOfDay = 720,
            priorityText = "3",
            enabled = false,
        )

        val draft = state.toDraft()!!

        assertEquals("Homework", draft.name)
        assertEquals(ScheduleMode.STUDY, draft.mode)
        assertEquals(ProtectionAction.SOFT_BLOCK, draft.action)
        assertEquals(ScheduleDays.of(DayOfWeek.SATURDAY), draft.days)
        assertEquals(600, draft.window.startMinuteOfDay)
        assertEquals(720, draft.window.endMinuteOfDay)
        assertEquals(3, draft.priority)
        assertFalse(draft.enabled)
    }

    @Test
    fun test4_nameIsTrimmedForPersistence() {
        val draft = ScheduleEditorState.create().copy(name = "  Homework  ").toDraft()!!

        assertEquals("Homework", draft.name)
    }

    @Test
    fun test5_theDraftCarriesTheExistingScheduleIdOnSave() {
        val state = ScheduleEditorState.from(rule(id = 42L), emptySet())

        // The editor keeps the id, and the draft adopts it, so an edit updates in place.
        assertEquals(42L, state.scheduleId)
        assertEquals(42L, state.toDraft()!!.withId(state.scheduleId!!).id)
    }

    // ---- 11/12. name validation --------------------------------------------

    @Test
    fun test11_blankNameIsInvalid() {
        val state = ScheduleEditorState.create().copy(name = "")

        assertEquals(ScheduleEditorValidation.Invalid(R.string.schedule_error_name), state.validate())
        assertNull("an invalid state yields no draft", state.toDraft())
    }

    @Test
    fun test12_whitespaceOnlyNameIsInvalid() {
        val state = ScheduleEditorState.create().copy(name = "   ")

        assertEquals(ScheduleEditorValidation.Invalid(R.string.schedule_error_name), state.validate())
        assertEquals("", state.trimmedName)
        assertNull(state.toDraft())
    }

    // ---- 13/14. time validation --------------------------------------------

    @Test
    fun test13_equalStartAndEndIsInvalid() {
        val state = ScheduleEditorState.create().copy(name = "Bad window", startMinuteOfDay = 600, endMinuteOfDay = 600)

        assertEquals(ScheduleEditorValidation.Invalid(R.string.schedule_error_time), state.validate())
        assertNull(state.toDraft())
    }

    @Test
    fun test14_crossMidnightIsAccepted() {
        val state = ScheduleEditorState.create().copy(name = "Night", startMinuteOfDay = 1320, endMinuteOfDay = 360)

        assertEquals(ScheduleEditorValidation.Valid, state.validate())
        assertTrue(state.crossesMidnight)
        val draft = state.toDraft()!!
        assertEquals(1320, draft.window.startMinuteOfDay)
        assertEquals(360, draft.window.endMinuteOfDay)
    }

    @Test
    fun test14_aNormalSameDayWindowIsAccepted() {
        val state = ScheduleEditorState.create().copy(name = "Daytime", startMinuteOfDay = 540, endMinuteOfDay = 660)

        assertEquals(ScheduleEditorValidation.Valid, state.validate())
        assertFalse(state.crossesMidnight)
    }

    // ---- 15. priority validation -------------------------------------------

    @Test
    fun test15_nonNumericPriorityIsInvalid() {
        val state = ScheduleEditorState.create().copy(name = "Study", priorityText = "")

        assertNull(state.priority)
        assertEquals(ScheduleEditorValidation.Invalid(R.string.schedule_error_priority), state.validate())
        assertNull(state.toDraft())
    }

    @Test
    fun test15_negativePriorityIsAcceptedBecauseTheDomainAllowsIt() {
        val state = ScheduleEditorState.create().copy(name = "Study", priorityText = "-3")

        assertEquals(-3, state.priority)
        assertEquals(ScheduleEditorValidation.Valid, state.validate())
        assertEquals(-3, state.toDraft()!!.priority)
    }

    // ---- empty days ---------------------------------------------------------

    @Test
    fun test_emptyDaySelectionIsInvalid() {
        val state = ScheduleEditorState.create().copy(name = "Study", days = emptySet())

        assertEquals(ScheduleEditorValidation.Invalid(R.string.schedule_error_days), state.validate())
        assertNull(state.toDraft())
    }

    // ---- 16/17/18. selection and mode/action persistence -------------------

    @Test
    fun test16_selectedDaysBecomeTheDomainMask() {
        val state = ScheduleEditorState.create()
            .copy(name = "Study", days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY))

        val draft = state.toDraft()!!
        assertEquals(ScheduleDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY), draft.days)
        assertEquals(ScheduleDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY).mask, draft.days.mask)
    }

    @Test
    fun test16_dayToggleAddsAndRemoves() {
        val state = ScheduleEditorState.create().copy(days = setOf(DayOfWeek.MONDAY))

        val added = state.withDayToggled(DayOfWeek.TUESDAY)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), added.days)

        val removed = added.withDayToggled(DayOfWeek.MONDAY)
        assertEquals(setOf(DayOfWeek.TUESDAY), removed.days)
    }

    @Test
    fun test16_dayPresetsCoverTheRequiredSelections() {
        assertEquals(7, DayPresets.ALL.size)
        assertEquals(5, DayPresets.WEEKDAYS.size)
        assertEquals(2, DayPresets.WEEKENDS.size)
        assertEquals(DayPresets.ALL, DayPresets.WEEKDAYS + DayPresets.WEEKENDS)
    }

    @Test
    fun test17_actionSelectionPersistsToTheDraft() {
        SCHEDULE_ACTIONS.forEach { action ->
            val draft = ScheduleEditorState.create().copy(name = "Study", action = action).toDraft()!!
            assertEquals(action, draft.action)
        }
    }

    @Test
    fun test18_modeSelectionPersistsToTheDraft() {
        ScheduleMode.entries.forEach { mode ->
            val draft = ScheduleEditorState.create().copy(name = "Study", mode = mode).toDraft()!!
            assertEquals(mode, draft.mode)
        }
    }

    @Test
    fun test_modeIsDescriptiveAndNeverChangesTheAction() {
        // Selecting a different mode must not silently change the action or any other field.
        val base = ScheduleEditorState.create().copy(name = "Study", action = ProtectionAction.SOFT_BLOCK)
        ScheduleMode.entries.forEach { mode ->
            val changed = base.copy(mode = mode)
            assertEquals(ProtectionAction.SOFT_BLOCK, changed.action)
            assertEquals(base.days, changed.days)
            assertEquals(base.startMinuteOfDay, changed.startMinuteOfDay)
            assertEquals(base.endMinuteOfDay, changed.endMinuteOfDay)
            assertEquals(base.priorityText, changed.priorityText)
            assertEquals(base.selectedPackages, changed.selectedPackages)
        }
    }

    // ---- 21/22. app selection ----------------------------------------------

    @Test
    fun test22_zeroSelectedAppsMeansNoAppsNotAllApps() {
        val state = ScheduleEditorState.create().copy(name = "Study", selectedPackages = emptySet())

        // A schedule with no targets is still a valid schedule; it simply affects nothing.
        assertEquals(emptySet<String>(), state.targetPackages())
        assertEquals(emptySet<String>(), state.toDraft()!!.let { state.targetPackages() })
        assertTrue(state.validate() is ScheduleEditorValidation.Valid)
    }

    // ---- 23. every action label resolves from resources --------------------

    @Test
    fun test23_everyScheduleActionHasLabelsAndNoDomainOnlyActionIsOffered() {
        assertEquals(
            listOf(
                ProtectionAction.ALLOW,
                ProtectionAction.WARNING,
                ProtectionAction.SOFT_BLOCK,
                ProtectionAction.HARD_BLOCK,
                ProtectionAction.MUTE,
            ),
            SCHEDULE_ACTIONS,
        )
        assertFalse("DIM is not a schedule action", ProtectionAction.DIM in SCHEDULE_ACTIONS)
        assertFalse("BLUR is not a schedule action", ProtectionAction.BLUR in SCHEDULE_ACTIONS)
        assertFalse(
            "BLACK_SCREEN is not a schedule action",
            ProtectionAction.BLACK_SCREEN in SCHEDULE_ACTIONS,
        )

        SCHEDULE_ACTIONS.forEach { action ->
            assertTrue("action $action needs a label", actionLabelRes(action) != 0)
            assertTrue("action $action needs a description", actionDescriptionRes(action) != 0)
        }
    }

    @Test
    fun test23_everyDayAndModeHasALabel() {
        DayOfWeek.entries.forEach { assertTrue(dayLabelRes(it) != 0) }
        ScheduleMode.entries.forEach {
            assertTrue(modeLabelRes(it) != 0)
            assertTrue(modeDescriptionRes(it) != 0)
        }
    }

    // ---- time presentation --------------------------------------------------

    @Test
    fun timeFormattingIsZeroPaddedAndWholeMinute() {
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("07:00", formatMinuteOfDay(420))
        assertEquals("22:00", formatMinuteOfDay(1320))
        assertEquals("23:59", formatMinuteOfDay(1439))
        assertEquals("09:05", formatMinuteOfDay(9 * 60 + 5))
    }

    // ---- 24. a failed save keeps the input so it can be retried -------------

    @Test
    fun test24_aRejectedSaveLeavesTheInputIntactForRetry() {
        val state = ScheduleEditorState.create().copy(name = "")

        val message = (state.validate() as ScheduleEditorValidation.Invalid).messageRes
        val afterFailure = state.copy(errorMessageRes = message, saving = false)

        assertEquals("", afterFailure.name)
        assertEquals(message, afterFailure.errorMessageRes)
        assertFalse(afterFailure.busy)

        // Correcting the name makes the very same editor state saveable again.
        assertTrue(afterFailure.copy(name = "Fixed").validate() is ScheduleEditorValidation.Valid)
    }
}
