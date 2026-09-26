package uz.faceguard.app.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleResolver
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow

/**
 * Phase 5 Step 1 (pure JVM): active-schedule resolution.
 *
 * Times are always supplied explicitly. 2026-09-20 is a Sunday, so the week used below is
 * Sun 20 / Mon 21 / Tue 22 Sep 2026.
 */
class ScheduleResolverTest {

    // Late September 2026: 20 = Sun, 21 = Mon, 22 = Tue, 25 = Fri, 26 = Sat, 28 = Mon.
    private fun at(day: Int, hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 9, day, hour, minute)

    private fun rule(
        id: Long,
        name: String = "Rule $id",
        start: Pair<Int, Int> = 22 to 0,
        end: Pair<Int, Int> = 7 to 0,
        days: ScheduleDays = ScheduleDays.of(DayOfWeek.MONDAY),
        priority: Int = 0,
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        enabled: Boolean = true,
    ) = ScheduleRule(
        id = id,
        name = name,
        mode = ScheduleMode.CUSTOM,
        window = ScheduleWindow(LocalTime.of(start.first, start.second), LocalTime.of(end.first, end.second)),
        days = days,
        action = action,
        priority = priority,
        enabled = enabled,
    )

    private fun active(schedules: List<ScheduleRule>, whenTime: LocalDateTime): ScheduleRule? =
        (ScheduleResolver.resolve(schedules, whenTime) as? ScheduleResolution.ActiveSchedule)?.schedule

    private fun conflict(schedules: List<ScheduleRule>, whenTime: LocalDateTime): List<ScheduleRule>? =
        (ScheduleResolver.resolve(schedules, whenTime) as? ScheduleResolution.ScheduleConflict)?.schedules

    // ---- no active / single active ------------------------------------------

    @Test
    fun emptyScheduleListIsNoActiveSchedule() {
        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(emptyList(), at(22, 1, 0)))
    }

    @Test
    fun nothingActiveReturnsNoActiveSchedule() {
        // Window is 09:00-17:00 Monday; 18:00 is outside it.
        val r = rule(1, start = 9 to 0, end = 17 to 0)

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(r), at(21, 18, 0)))
    }

    @Test
    fun oneActiveScheduleIsReturned() {
        val r = rule(1)

        assertEquals(r, active(listOf(r), at(21, 23, 0)))
    }

    @Test
    fun dayMismatchIsInactive() {
        // Monday-only window evaluated on Tuesday (outside the after-midnight portion).
        val r = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY))

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(r), at(22, 23, 0)))
    }

    @Test
    fun disabledScheduleIsIgnored() {
        val r = rule(1, enabled = false)

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(r), at(21, 23, 0)))
    }

    @Test
    fun disabledHighestPriorityDoesNotWin() {
        val disabledHigh = rule(1, priority = 100, enabled = false)
        val enabledLow = rule(2, priority = 1)

        assertEquals(enabledLow, active(listOf(disabledHigh, enabledLow), at(21, 23, 0)))
    }

    // ---- boundaries ---------------------------------------------------------

    @Test
    fun exactStartBoundaryIsActive() {
        val r = rule(1) // 22:00-07:00 Monday

        assertTrue(ScheduleResolver.isActive(r, at(21, 22, 0)))
        assertEquals(r, active(listOf(r), at(21, 22, 0)))
    }

    @Test
    fun justBeforeStartIsInactive() {
        val r = rule(1)

        assertFalse(ScheduleResolver.isActive(r, at(21, 21, 59)))
        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(r), at(21, 21, 59)))
    }

    @Test
    fun exactEndBoundaryIsInactive() {
        val r = rule(1)

        assertFalse(ScheduleResolver.isActive(r, at(22, 7, 0)))
        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(r), at(22, 7, 0)))
    }

    @Test
    fun oneMinuteBeforeEndIsActive() {
        val r = rule(1)

        assertTrue(ScheduleResolver.isActive(r, at(22, 6, 59)))
    }

    // ---- cross-midnight -----------------------------------------------------

    @Test
    fun mondayCrossMidnightTimelineIsExact() {
        // The contract's table for Monday 22:00-07:00 with Monday selected.
        val r = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY))

        assertFalse("Mon 21:59", ScheduleResolver.isActive(r, at(21, 21, 59)))
        assertTrue("Mon 22:00", ScheduleResolver.isActive(r, at(21, 22, 0)))
        assertTrue("Mon 23:59", ScheduleResolver.isActive(r, at(21, 23, 59)))
        assertTrue("Tue 00:00", ScheduleResolver.isActive(r, at(22, 0, 0)))
        assertTrue("Tue 06:59", ScheduleResolver.isActive(r, at(22, 6, 59)))
        assertFalse("Tue 07:00", ScheduleResolver.isActive(r, at(22, 7, 0)))
    }

    @Test
    fun crossMidnightContinuationDoesNotRequireTheContinuationDayToBeSelected() {
        // Only Monday is selected; Tuesday is nevertheless covered by Monday's interval.
        val r = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY))

        assertEquals(r, active(listOf(r), at(22, 0, 30)))
        assertFalse("Tuesday is not selected as an owning day", r.days.contains(DayOfWeek.TUESDAY))
    }

    @Test
    fun sundayCrossMidnightContinuatesIntoMonday() {
        // Sunday 22:00-07:00, only Sunday selected.
        val r = rule(1, days = ScheduleDays.of(DayOfWeek.SUNDAY))

        assertTrue("Sun 22:00", ScheduleResolver.isActive(r, at(20, 22, 0)))
        assertTrue("Sun 23:00", ScheduleResolver.isActive(r, at(20, 23, 0)))
        assertTrue("Mon 00:30", ScheduleResolver.isActive(r, at(21, 0, 30)))
        assertTrue("Mon 06:59", ScheduleResolver.isActive(r, at(21, 6, 59)))
        assertFalse("Mon 07:00", ScheduleResolver.isActive(r, at(21, 7, 0)))
    }

    @Test
    fun sameDayWindowIsNotExtendedPastItsEnd() {
        val r = rule(1, start = 10 to 0, end = 12 to 0, days = ScheduleDays.of(DayOfWeek.MONDAY))

        assertTrue(ScheduleResolver.isActive(r, at(21, 11, 59)))
        assertFalse(ScheduleResolver.isActive(r, at(21, 12, 0)))
        assertFalse(ScheduleResolver.isActive(r, at(20, 11, 0)))
    }

    // ---- priority -----------------------------------------------------------

    @Test
    fun higherPriorityWinsWhenUnique() {
        val low = rule(1, priority = 1)
        val high = rule(2, priority = 5)

        assertEquals(high, active(listOf(low, high), at(21, 23, 0)))
    }

    @Test
    fun lowerPriorityIsIrrelevantWhenAUniqueHigherPriorityExists() {
        val a = rule(1, priority = 1)
        val b = rule(2, priority = 8)
        val c = rule(3, priority = 2)

        val result = ScheduleResolver.resolve(listOf(a, b, c), at(21, 23, 0))

        assertEquals(ScheduleResolution.ActiveSchedule(b), result)
    }

    @Test
    fun inputOrderDoesNotChooseTheWinner() {
        val low = rule(1, priority = 1)
        val high = rule(2, priority = 5)

        assertEquals(high, active(listOf(low, high), at(21, 23, 0)))
        assertEquals(high, active(listOf(high, low), at(21, 23, 0)))
    }

    @Test
    fun negativePrioritiesOrderCorrectly() {
        val low = rule(1, priority = -5)
        val high = rule(2, priority = -1)

        assertEquals(high, active(listOf(low, high), at(21, 23, 0)))
    }

    // ---- conflict -----------------------------------------------------------

    @Test
    fun equalHighestPriorityProducesConflict() {
        val a = rule(1, priority = 4)
        val b = rule(2, priority = 4)

        val result = ScheduleResolver.resolve(listOf(a, b), at(21, 23, 0))

        assertTrue("a tie must never be resolved silently", result is ScheduleResolution.ScheduleConflict)
        assertEquals(setOf(a, b), conflict(listOf(a, b), at(21, 23, 0))?.toSet())
    }

    @Test
    fun allTiedSchedulesAreIncluded() {
        val a = rule(1, priority = 4)
        val b = rule(2, priority = 4)
        val c = rule(3, priority = 4)

        val winners = conflict(listOf(a, b, c), at(21, 23, 0))

        assertEquals(3, winners?.size)
        assertEquals(setOf(a, b, c), winners?.toSet())
    }

    @Test
    fun tieExcludesStrictlyLowerPriorities() {
        val top1 = rule(1, priority = 9)
        val top2 = rule(2, priority = 9)
        val lower = rule(3, priority = 3)

        val winners = conflict(listOf(top1, top2, lower), at(21, 23, 0))

        assertEquals(setOf(top1, top2), winners?.toSet())
    }

    @Test
    fun tieIsNotBrokenByIdNameModeOrOrder() {
        // Two schedules that differ only in the fields a naive tie-breaker might use.
        val first = rule(1, name = "Aaa", priority = 7)
        val second = rule(2, name = "Zzz", priority = 7)

        // Neither order, and neither id/name, may promote one to the winner.
        listOf(listOf(first, second), listOf(second, first)).forEach { input ->
            assertEquals(
                ScheduleResolution.ScheduleConflict::class.java,
                ScheduleResolver.resolve(input, at(21, 23, 0)).javaClass,
            )
        }
    }

    @Test
    fun conflictOrderingIsDeterministicByNameThenId() {
        val zulu = rule(1, name = "Zulu", priority = 2)
        val alpha = rule(2, name = "Alpha", priority = 2)

        assertEquals(listOf(alpha, zulu), conflict(listOf(zulu, alpha), at(21, 23, 0)))
        assertEquals(listOf(alpha, zulu), conflict(listOf(alpha, zulu), at(21, 23, 0)))
    }

    @Test
    fun conflictOrderingFallsBackToIdForEqualNames() {
        val later = rule(9, name = "Same", priority = 1)
        val earlier = rule(3, name = "Same", priority = 1)

        assertEquals(listOf(earlier, later), conflict(listOf(later, earlier), at(21, 23, 0)))
    }

    @Test
    fun conflictOnlyContainsActiveSchedules() {
        val activeTieA = rule(1, priority = 5)
        val activeTieB = rule(2, priority = 5)
        // Same priority but a different day, so not active and must not join the conflict.
        val otherDay = rule(3, priority = 5, days = ScheduleDays.of(DayOfWeek.FRIDAY))

        val winners = conflict(listOf(activeTieA, activeTieB, otherDay), at(21, 23, 0))

        assertEquals(setOf(activeTieA, activeTieB), winners?.toSet())
    }

    @Test
    fun conflictIsReturnedEvenWhenWinnersHaveDifferentActions() {
        val block = rule(1, priority = 3, action = ProtectionAction.HARD_BLOCK)
        val allow = rule(2, priority = 3, action = ProtectionAction.ALLOW)

        val winners = conflict(listOf(block, allow), at(21, 23, 0))

        assertEquals(setOf(block, allow), winners?.toSet())
    }

    // ---- instant + zone -----------------------------------------------------

    @Test
    fun instantResolutionMatchesLocalResolution() {
        val r = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY))
        val local = at(22, 1, 0)

        val viaLocal = ScheduleResolver.resolve(listOf(r), local)
        val viaInstant = ScheduleResolver.resolve(listOf(r), local.atZone(ZoneId.of("Asia/Tashkent")).toInstant(), ZoneId.of("Asia/Tashkent"))

        assertEquals(viaLocal, viaInstant)
        assertEquals(r, (viaInstant as ScheduleResolution.ActiveSchedule).schedule)
    }

    @Test
    fun instantResolutionDependsOnTheSuppliedZone() {
        val r = rule(1, days = ScheduleDays.of(DayOfWeek.MONDAY))
        // 2026-09-21T20:00Z is Monday 20:00 in UTC (before the 22:00 start) but Tuesday
        // 01:00 in Tashkent (UTC+5), which is inside Monday's after-midnight portion.
        val instant = Instant.parse("2026-09-21T20:00:00Z")

        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            ScheduleResolver.resolve(listOf(r), instant, ZoneId.of("UTC")),
        )
        assertEquals(
            r,
            (ScheduleResolver.resolve(listOf(r), instant, ZoneId.of("Asia/Tashkent")) as ScheduleResolution.ActiveSchedule).schedule,
        )
    }

    @Test
    fun distinctCandidatesWithDifferentPrioritiesResolveToTheHighest() {
        // Several active candidates at once; only the unique highest wins.
        val p1 = rule(1, start = 0 to 0, end = 23 to 59, days = ScheduleDays.ALL, priority = 1)
        val p2 = rule(2, start = 0 to 0, end = 23 to 59, days = ScheduleDays.ALL, priority = 2)
        val p3 = rule(3, start = 0 to 0, end = 23 to 59, days = ScheduleDays.ALL, priority = 3)

        assertEquals(p3, active(listOf(p1, p2, p3), at(21, 12, 0)))
    }
}
