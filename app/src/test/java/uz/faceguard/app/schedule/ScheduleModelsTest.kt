package uz.faceguard.app.schedule

import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow

/**
 * Phase 5 Step 1 (pure JVM): the schedule value objects.
 *
 * The masks, the minute-of-day encoding and the rejections are all part of the storage
 * and resolution contract that later steps depend on, so each is asserted explicitly
 * rather than implied. No Android, no clock, no I/O.
 */
class ScheduleModelsTest {

    private fun window(startH: Int, startM: Int, endH: Int, endM: Int) =
        ScheduleWindow(LocalTime.of(startH, startM), LocalTime.of(endH, endM))

    // ---- ScheduleDays -------------------------------------------------------

    @Test
    fun mondayIsTheLowestBit() {
        val monday = ScheduleDays.of(DayOfWeek.MONDAY)

        assertEquals(0b0000001, monday.mask)
        assertTrue(monday.contains(DayOfWeek.MONDAY))
        assertFalse(monday.contains(DayOfWeek.SUNDAY))
        assertEquals(listOf(DayOfWeek.MONDAY), monday.days)
    }

    @Test
    fun sundayIsTheHighestBit() {
        val sunday = ScheduleDays.of(DayOfWeek.SUNDAY)

        assertEquals(0b1000000, sunday.mask)
        assertTrue(sunday.contains(DayOfWeek.SUNDAY))
        assertFalse(sunday.contains(DayOfWeek.MONDAY))
    }

    @Test
    fun eachDayHasItsOwnBitInOrder() {
        // Monday..Sunday must map to bits 0..6 respectively.
        val expected = listOf(1, 2, 4, 8, 16, 32, 64)
        DayOfWeek.entries.forEachIndexed { index, day ->
            assertEquals("bit for $day", expected[index], ScheduleDays.of(day).mask)
        }
    }

    @Test
    fun multipleDaysCombineIntoOneMask() {
        val days = ScheduleDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

        assertEquals(0b0010101, days.mask)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), days.days)
        assertTrue(days.contains(DayOfWeek.MONDAY))
        assertFalse(days.contains(DayOfWeek.TUESDAY))
    }

    @Test
    fun containsIsExhaustiveAcrossTheWeek() {
        val days = ScheduleDays.WEEKENDS

        assertTrue(days.contains(DayOfWeek.SATURDAY))
        assertTrue(days.contains(DayOfWeek.SUNDAY))
        listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        ).forEach { assertFalse("$it is not a weekend", days.contains(it)) }
    }

    @Test
    fun allSelectsEveryDay() {
        assertEquals(0b1111111, ScheduleDays.ALL.mask)
        assertEquals(7, ScheduleDays.ALL.days.size)
        assertTrue(ScheduleDays.ALL.isEveryDay)
        assertEquals(DayOfWeek.entries.toList(), ScheduleDays.ALL.days)
    }

    @Test
    fun weekdaysAndWeekendsAreComplements() {
        assertEquals(0b0011111, ScheduleDays.WEEKDAYS.mask)
        assertEquals(0b1100000, ScheduleDays.WEEKENDS.mask)
        assertEquals(ScheduleDays.ALL_MASK, ScheduleDays.WEEKDAYS.mask or ScheduleDays.WEEKENDS.mask)
        assertEquals(0, ScheduleDays.WEEKDAYS.mask and ScheduleDays.WEEKENDS.mask)
        assertFalse(ScheduleDays.WEEKDAYS.isEveryDay)
    }

    @Test
    fun emptySelectionIsRejected() {
        // Every route to "no days" must fail: vararg, set and raw constructor.
        assertThrows(IllegalArgumentException::class.java) { ScheduleDays.of() }
        assertThrows(IllegalArgumentException::class.java) { ScheduleDays.of(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleDays(0) }
    }

    @Test
    fun bitsOutsideMondayToSundayAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleDays(0b10000000) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleDays(ScheduleDays.ALL_MASK or 0b10000000) }
    }

    @Test
    fun setAndVarargFactoriesAgree() {
        val viaVararg = ScheduleDays.of(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)
        val viaSet = ScheduleDays.of(setOf(DayOfWeek.SATURDAY, DayOfWeek.TUESDAY))

        assertEquals(viaVararg, viaSet)
        assertEquals(viaVararg.mask, viaSet.mask)
    }

    // ---- ScheduleWindow -----------------------------------------------------

    @Test
    fun normalIntervalDoesNotCrossMidnight() {
        val w = window(9, 0, 17, 0)

        assertFalse(w.crossesMidnight)
        assertEquals(9 * 60, w.startMinuteOfDay)
        assertEquals(17 * 60, w.endMinuteOfDay)
        assertTrue(w.containsMinute(9 * 60))
        assertTrue(w.containsMinute(12 * 60))
        assertFalse(w.containsMinute(17 * 60))
        assertFalse(w.containsMinute(8 * 60))
    }

    @Test
    fun crossMidnightIntervalIsExact() {
        val w = window(22, 0, 7, 0)

        assertTrue(w.crossesMidnight)
        assertEquals(1320, w.startMinuteOfDay)
        assertEquals(420, w.endMinuteOfDay)
    }

    @Test
    fun startIsInclusiveAndEndIsExclusive() {
        val w = window(22, 0, 7, 0)

        assertTrue("exact start is inside", w.containsMinute(1320))
        assertTrue("one minute before end is inside", w.containsMinute(419))
        assertFalse("exact end is outside", w.containsMinute(420))
        assertFalse("one minute before start is outside", w.containsMinute(1319))
    }

    @Test
    fun minuteOfDayConversionIsCanonical() {
        assertEquals(0, window(0, 0, 1, 0).startMinuteOfDay)
        assertEquals(1439, window(0, 0, 23, 59).endMinuteOfDay)
        assertEquals(630, window(10, 30, 11, 0).startMinuteOfDay)
    }

    @Test
    fun ofMinutesRoundTripsTheEncoding() {
        val w = ScheduleWindow.ofMinutes(1320, 420)

        assertEquals(LocalTime.of(22, 0), w.start)
        assertEquals(LocalTime.of(7, 0), w.end)
        assertEquals(1320, w.startMinuteOfDay)
        assertEquals(420, w.endMinuteOfDay)
        assertTrue(w.crossesMidnight)
    }

    @Test
    fun startEqualsEndIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleWindow.ofMinutes(600, 600) }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleWindow(LocalTime.of(9, 0), LocalTime.of(9, 0))
        }
    }

    @Test
    fun secondsAndNanosAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleWindow(LocalTime.of(9, 0, 30), LocalTime.of(17, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleWindow(LocalTime.of(9, 0), LocalTime.of(17, 0, 0, 1))
        }
    }

    @Test
    fun twentyFourHundredIsRejectedNotNormalisedToMidnight() {
        // 1440 is a notional 24:00; folding it to 0 would turn a full day into 00:00-00:00.
        assertThrows(IllegalArgumentException::class.java) { ScheduleWindow.ofMinutes(0, 1440) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleWindow.ofMinutes(1440, 420) }
    }

    @Test
    fun outOfRangeMinuteOfDayIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleWindow.ofMinutes(-1, 420) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleWindow.ofMinutes(1320, 2000) }
    }

    @Test
    fun containsMinuteRejectsOutOfRangeInput() {
        val w = window(9, 0, 17, 0)

        assertThrows(IllegalArgumentException::class.java) { w.containsMinute(-1) }
        assertThrows(IllegalArgumentException::class.java) { w.containsMinute(1440) }
    }

    // ---- ScheduleRule -------------------------------------------------------

    private fun rule(
        id: Long = 1L,
        name: String = "Bedtime",
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        enabled: Boolean = true,
    ) = ScheduleRule(
        id = id,
        name = name,
        mode = ScheduleMode.SLEEP,
        window = window(22, 0, 7, 0),
        days = ScheduleDays.of(DayOfWeek.MONDAY),
        action = action,
        enabled = enabled,
    )

    @Test
    fun validRuleIsAcceptedAndDefaultsEnabled() {
        val r = ScheduleRule(
            id = 7L,
            name = "Study time",
            mode = ScheduleMode.STUDY,
            window = window(16, 0, 18, 0),
            days = ScheduleDays.WEEKDAYS,
            action = ProtectionAction.SOFT_BLOCK,
        )

        assertTrue(r.enabled)
        assertEquals(0, r.priority)
        assertEquals(ScheduleMode.STUDY, r.mode)
    }

    @Test
    fun nonPositiveIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { rule(id = 0L) }
        assertThrows(IllegalArgumentException::class.java) { rule(id = -3L) }
    }

    @Test
    fun blankNameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { rule(name = "") }
        assertThrows(IllegalArgumentException::class.java) { rule(name = "   ") }
    }

    @Test
    fun unimplementedActionsAreRejected() {
        // DIM / BLUR / BLACK_SCREEN exist in the domain but are not enforced; a schedule
        // must not be able to carry one.
        assertThrows(IllegalArgumentException::class.java) { rule(action = ProtectionAction.DIM) }
        assertThrows(IllegalArgumentException::class.java) { rule(action = ProtectionAction.BLUR) }
        assertThrows(IllegalArgumentException::class.java) { rule(action = ProtectionAction.BLACK_SCREEN) }
    }

    @Test
    fun everyImplementedActionIsAccepted() {
        listOf(
            ProtectionAction.ALLOW,
            ProtectionAction.WARNING,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
            ProtectionAction.MUTE,
        ).forEach { action ->
            assertEquals(action, rule(action = action).action)
        }
    }

    @Test
    fun disabledRuleRetainsItsConfiguration() {
        val r = rule(enabled = false)

        assertFalse(r.enabled)
        assertEquals("Bedtime", r.name)
    }

    @Test
    fun allModesExistAndAreDistinct() {
        assertEquals(
            listOf("NORMAL", "STUDY", "SLEEP", "SCHOOL", "CUSTOM"),
            ScheduleMode.entries.map { it.name },
        )
    }
}
