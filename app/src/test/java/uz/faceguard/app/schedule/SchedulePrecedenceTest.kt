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
 * Phase 5 Step 3: precedence and overlap resolution.
 *
 * These exercise the *production* [ScheduleResolver] through its public API — the algorithm is
 * never re-implemented here. Each case is a distinct configuration with an unambiguous expected
 * outcome, so the tests describe the approved precedence contract rather than the code.
 *
 * 2026-09-20 is a Sunday, so the week used throughout is Sun 20 / Mon 21 / Tue 22 Sep 2026.
 */
class SchedulePrecedenceTest {

    private fun mon(hour: Int, minute: Int = 0): LocalDateTime = LocalDateTime.of(2026, 9, 21, hour, minute)
    private fun tue(hour: Int, minute: Int = 0): LocalDateTime = LocalDateTime.of(2026, 9, 22, hour, minute)

    private fun rule(
        id: Long,
        name: String = "Rule $id",
        days: ScheduleDays = ScheduleDays.of(DayOfWeek.MONDAY),
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
        priority: Int,
        enabled: Boolean = true,
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        mode: ScheduleMode = ScheduleMode.CUSTOM,
    ) = ScheduleRule(
        id = id,
        name = name,
        mode = mode,
        window = ScheduleWindow(LocalTime.of(start.first, start.second), LocalTime.of(end.first, end.second)),
        days = days,
        action = action,
        priority = priority,
        enabled = enabled,
    )

    /** The single winner, or null when the result is not a single-winner resolution. */
    private fun winner(schedules: List<ScheduleRule>, at: LocalDateTime): ScheduleRule? =
        (ScheduleResolver.resolve(schedules, at) as? ScheduleResolution.ActiveSchedule)?.schedule

    /** The conflict members, or null when the result is not a conflict. */
    private fun conflict(schedules: List<ScheduleRule>, at: LocalDateTime): List<ScheduleRule>? =
        (ScheduleResolver.resolve(schedules, at) as? ScheduleResolution.ScheduleConflict)?.schedules

    /** Assert the resolution is a conflict whose members are exactly [expected]. */
    private fun assertConflict(
        schedules: List<ScheduleRule>,
        at: LocalDateTime,
        expected: List<ScheduleRule>,
    ) {
        val result = ScheduleResolver.resolve(schedules, at)
        assertTrue("expected a conflict at $at, was $result", result is ScheduleResolution.ScheduleConflict)
        assertEquals(expected, (result as ScheduleResolution.ScheduleConflict).schedules)
    }

    // ================================================================
    // CASE A — two non-overlapping schedules: only the active one applies
    // ================================================================

    @Test
    fun caseA_nonOverlappingSchedules_applyIndependently() {
        val morning = rule(1, name = "Morning", start = 8 to 0, end = 10 to 0, priority = 10)
        val afternoon = rule(2, name = "Afternoon", start = 12 to 0, end = 14 to 0, priority = 10)
        val all = listOf(morning, afternoon)

        assertEquals(morning, winner(all, mon(9, 0)))
        assertEquals(afternoon, winner(all, mon(13, 0)))
        assertEquals(
            ScheduleResolution.NoActiveSchedule,
            ScheduleResolver.resolve(all, mon(11, 0)),
        )
    }

    // ================================================================
    // CASE B — overlapping, different priorities: higher wins
    // ================================================================

    @Test
    fun caseB_overlappingDifferentPriorities_higherWins() {
        val low = rule(1, name = "Low", start = 8 to 0, end = 12 to 0, priority = 10)
        val high = rule(2, name = "High", start = 10 to 0, end = 14 to 0, priority = 20)

        assertEquals(high, winner(listOf(low, high), mon(11, 0)))
        assertEquals(high, winner(listOf(high, low), mon(11, 0)))
    }

    // ================================================================
    // CASE C — overlapping, equal priority: conflict
    // ================================================================

    @Test
    fun caseC_overlappingEqualPriorities_conflict() {
        val a = rule(1, name = "A", start = 8 to 0, end = 12 to 0, priority = 10)
        val b = rule(2, name = "B", start = 10 to 0, end = 14 to 0, priority = 10)

        assertConflict(listOf(a, b), mon(11, 0), listOf(a, b))
    }

    // ================================================================
    // CASE D — three overlapping 10/20/30: the 30 wins
    // ================================================================

    @Test
    fun caseD_threeOverlapping_10_20_30_highestWins() {
        val p10 = rule(1, name = "P10", start = 8 to 0, end = 18 to 0, priority = 10)
        val p20 = rule(2, name = "P20", start = 9 to 0, end = 17 to 0, priority = 20)
        val p30 = rule(3, name = "P30", start = 10 to 0, end = 16 to 0, priority = 30)

        assertEquals(p30, winner(listOf(p10, p20, p30), mon(12, 0)))
    }

    // ================================================================
    // CASE E — three overlapping 10/20/20: conflict of exactly the two 20s
    // ================================================================

    @Test
    fun caseE_threeOverlapping_10_20_20_conflictOfTheTwoTied() {
        val p10 = rule(1, name = "P10", start = 8 to 0, end = 18 to 0, priority = 10)
        val p20a = rule(2, name = "P20a", start = 9 to 0, end = 17 to 0, priority = 20)
        val p20b = rule(3, name = "P20b", start = 10 to 0, end = 16 to 0, priority = 20)

        assertConflict(listOf(p10, p20a, p20b), mon(12, 0), listOf(p20a, p20b))
    }

    // ================================================================
    // CASE F — four overlapping 10/20/20/30: the 30 wins, no conflict
    // ================================================================

    @Test
    fun caseF_fourOverlapping_10_20_20_30_uniqueHighestWins() {
        val p10 = rule(1, name = "P10", start = 8 to 0, end = 18 to 0, priority = 10)
        val p20a = rule(2, name = "P20a", start = 9 to 0, end = 17 to 0, priority = 20)
        val p20b = rule(3, name = "P20b", start = 10 to 0, end = 16 to 0, priority = 20)
        val p30 = rule(4, name = "P30", start = 11 to 0, end = 15 to 0, priority = 30)
        val all = listOf(p10, p20a, p20b, p30)

        assertEquals(p30, winner(all, mon(12, 0)))
        assertEquals(
            "the tied 20s must not surface once a unique higher priority exists",
            ScheduleResolution.ActiveSchedule(p30),
            ScheduleResolver.resolve(all, mon(12, 0)),
        )
    }

    // ================================================================
    // CASE G — disabled highest priority is ignored
    // ================================================================

    @Test
    fun caseG_disabledHighestPriorityIsIgnored() {
        val disabledHigh = rule(1, name = "Disabled high", start = 8 to 0, end = 18 to 0, priority = 100, enabled = false)
        val active = rule(2, name = "Active", start = 8 to 0, end = 18 to 0, priority = 5)

        assertEquals(active, winner(listOf(disabledHigh, active), mon(12, 0)))
    }

    @Test
    fun caseG_disabledScheduleCannotCauseAConflict() {
        // The disabled rule ties the highest priority; it must not join a conflict.
        val active = rule(1, name = "Active", start = 8 to 0, end = 18 to 0, priority = 10)
        val disabledTie = rule(2, name = "Disabled tie", start = 8 to 0, end = 18 to 0, priority = 10, enabled = false)

        assertEquals(active, winner(listOf(active, disabledTie), mon(12, 0)))
    }

    @Test
    fun caseG_onlyDisabledSchedules_isNoActiveSchedule() {
        val a = rule(1, name = "A", start = 8 to 0, end = 18 to 0, priority = 10, enabled = false)
        val b = rule(2, name = "B", start = 9 to 0, end = 17 to 0, priority = 20, enabled = false)

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(a, b), mon(12, 0)))
    }

    // ================================================================
    // CASE H — inactive highest priority is ignored
    // ================================================================

    @Test
    fun caseH_inactiveHighestPriorityIsIgnored() {
        // The 100-priority rule applies to a different weekday, so it is inactive here.
        val inactiveHigh = rule(
            1,
            name = "Inactive high",
            days = ScheduleDays.of(DayOfWeek.FRIDAY),
            start = 8 to 0,
            end = 18 to 0,
            priority = 100,
        )
        val active = rule(2, name = "Active", start = 8 to 0, end = 18 to 0, priority = 5)

        assertEquals(active, winner(listOf(inactiveHigh, active), mon(12, 0)))
    }

    @Test
    fun caseH_inactiveByTimeIsIgnoredEvenAtHighPriority() {
        val inactiveHigh = rule(1, name = "Early", start = 0 to 0, end = 6 to 0, priority = 100)
        val active = rule(2, name = "Active", start = 8 to 0, end = 18 to 0, priority = 5)

        assertEquals(active, winner(listOf(inactiveHigh, active), mon(12, 0)))
    }

    @Test
    fun caseH_inactiveTieCannotCauseAConflict() {
        val active = rule(1, name = "Active", start = 8 to 0, end = 18 to 0, priority = 10)
        val inactiveTie = rule(2, name = "Inactive tie", start = 20 to 0, end = 22 to 0, priority = 10)

        assertEquals(active, winner(listOf(active, inactiveTie), mon(12, 0)))
    }

    // ================================================================
    // CASE I — identity fields are never a tie-breaker
    // ================================================================

    @Test
    fun caseI_sameNameDifferentPriority_higherPriorityWins() {
        // Identical name, different id: identity must not decide — the priority does.
        val low = rule(1, name = "Bedtime", start = 8 to 0, end = 18 to 0, priority = 5)
        val high = rule(2, name = "Bedtime", start = 8 to 0, end = 18 to 0, priority = 9)

        assertEquals(high, winner(listOf(low, high), mon(12, 0)))
        assertEquals(high, winner(listOf(high, low), mon(12, 0)))
    }

    @Test
    fun caseI_sameNameSamePriority_conflictDespiteIdentity() {
        val a = rule(1, name = "Bedtime", start = 8 to 0, end = 18 to 0, priority = 7)
        val b = rule(2, name = "Bedtime", start = 8 to 0, end = 18 to 0, priority = 7)

        assertConflict(listOf(a, b), mon(12, 0), listOf(a, b))
    }

    @Test
    fun caseI_lowerIdDoesNotWinAnEqualPriorityTie() {
        // The "smaller id" rule belongs to the lower-priority schedule, so if id were a
        // tie-breaker it would wrongly win; instead the tie must surface as a conflict.
        val lowId = rule(1, name = "Zzz", start = 8 to 0, end = 18 to 0, priority = 7)
        val highId = rule(99, name = "Aaa", start = 8 to 0, end = 18 to 0, priority = 7)

        assertEquals(ScheduleResolution.ScheduleConflict::class.java, ScheduleResolver.resolve(listOf(lowId, highId), mon(12, 0)).javaClass)
    }

    // ================================================================
    // CASE J — input order does not change the outcome
    // ================================================================

    @Test
    fun caseJ_winnerIsIndependentOfInputOrder() {
        val p10 = rule(1, name = "P10", start = 8 to 0, end = 18 to 0, priority = 10)
        val p20 = rule(2, name = "P20", start = 9 to 0, end = 17 to 0, priority = 20)
        val p30 = rule(3, name = "P30", start = 10 to 0, end = 16 to 0, priority = 30)

        val permutations = listOf(
            listOf(p10, p20, p30),
            listOf(p30, p20, p10),
            listOf(p20, p30, p10),
            listOf(p30, p10, p20),
            listOf(p10, p30, p20),
            listOf(p20, p10, p30),
        )

        permutations.forEach { order ->
            assertEquals("order $order", p30, winner(order, mon(12, 0)))
        }
    }

    @Test
    fun caseJ_conflictMembersAndOrderingAreIndependentOfInputOrder() {
        val p20a = rule(2, name = "Alpha", start = 8 to 0, end = 18 to 0, priority = 20)
        val p20b = rule(3, name = "Beta", start = 9 to 0, end = 17 to 0, priority = 20)
        val p20c = rule(4, name = "Gamma", start = 10 to 0, end = 16 to 0, priority = 20)
        val expectedPresentation = listOf(p20a, p20b, p20c) // (name, id) ordering

        val permutations = listOf(
            listOf(p20a, p20b, p20c),
            listOf(p20c, p20b, p20a),
            listOf(p20b, p20a, p20c),
            listOf(p20c, p20a, p20b),
        )

        permutations.forEach { order ->
            assertConflict(order, mon(12, 0), expectedPresentation)
        }
    }

    // ================================================================
    // CASE K — cross-midnight overlapping schedules
    // ================================================================

    @Test
    fun caseK_crossMidnightOverlap_higherPriorityWins() {
        val a = rule(1, name = "A", start = 20 to 0, end = 23 to 0, priority = 10)
        val b = rule(2, name = "B", start = 22 to 0, end = 7 to 0, priority = 20)
        val all = listOf(a, b)

        assertEquals("Monday 22:30 — both active, B wins", b, winner(all, mon(22, 30)))
        assertEquals("Tuesday 00:30 — only B is active", b, winner(all, tue(0, 30)))
    }

    @Test
    fun caseK_beforeTheOverlapOnlyAApplies() {
        val a = rule(1, name = "A", start = 20 to 0, end = 23 to 0, priority = 10)
        val b = rule(2, name = "B", start = 22 to 0, end = 7 to 0, priority = 20)

        assertEquals(a, winner(listOf(a, b), mon(21, 0)))
    }

    @Test
    fun caseK_afterBothWindowsEndNothingIsActive() {
        // B ends (exclusively) at 07:00 Tuesday; A ended at 23:00 Monday, so at 07:00 nothing runs.
        val a = rule(1, name = "A", start = 20 to 0, end = 23 to 0, priority = 10)
        val b = rule(2, name = "B", start = 22 to 0, end = 7 to 0, priority = 20)

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(a, b), tue(7, 0)))
    }

    // ================================================================
    // CASE L — cross-midnight conflict
    // ================================================================

    private val crossA = rule(1, name = "A", start = 22 to 0, end = 7 to 0, priority = 20)
    private val crossB = rule(2, name = "B", start = 23 to 0, end = 6 to 0, priority = 20)

    @Test
    fun caseL_conflictWhileBothCrossMidnightWindowsOverlap() {
        assertConflict(listOf(crossA, crossB), mon(23, 30), listOf(crossA, crossB))
    }

    @Test
    fun caseL_conflictContinuesAfterMidnight() {
        assertConflict(listOf(crossA, crossB), tue(2, 0), listOf(crossA, crossB))
    }

    @Test
    fun caseL_bothActiveJustBeforeBEnds() {
        assertConflict(listOf(crossA, crossB), tue(5, 59), listOf(crossA, crossB))
    }

    @Test
    fun caseL_atExactBEndOnlyARemains() {
        // B's end is exclusive, so at exactly 06:00 B is inactive and only A (22:00-07:00) runs.
        assertEquals(crossA, winner(listOf(crossA, crossB), tue(6, 0)))
        assertEquals(
            ScheduleResolution.ActiveSchedule(crossA),
            ScheduleResolver.resolve(listOf(crossA, crossB), tue(6, 0)),
        )
    }

    @Test
    fun caseL_afterBEndsOnlyARemains() {
        assertEquals(crossA, winner(listOf(crossA, crossB), tue(6, 30)))
    }

    @Test
    fun caseL_atExactAEndNothingIsActive() {
        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(crossA, crossB), tue(7, 0)))
    }

    @Test
    fun caseL_atExactAStartOnlyARuns() {
        // At 22:00 only A (22:00-07:00) has started; B begins at 23:00.
        assertEquals(crossA, winner(listOf(crossA, crossB), mon(22, 0)))
    }

    @Test
    fun caseL_exact23StartBBecomesActive() {
        // At 23:00 A is active and B has just started (start inclusive), so both tie.
        assertConflict(listOf(crossA, crossB), mon(23, 0), listOf(crossA, crossB))
    }

    // ================================================================
    // CASE L variant — [start, end) boundary regression
    // ================================================================

    @Test
    fun sameDayWindowHonoursStartInclusiveEndExclusive() {
        val r = rule(1, name = "Window", start = 8 to 0, end = 10 to 0, priority = 1)
        val all = listOf(r)

        assertFalse("07:59 is outside", ScheduleResolver.isActive(r, mon(7, 59)))
        assertTrue("08:00 is inside", ScheduleResolver.isActive(r, mon(8, 0)))
        assertTrue("09:59 is inside", ScheduleResolver.isActive(r, mon(9, 59)))
        assertFalse("10:00 is outside", ScheduleResolver.isActive(r, mon(10, 0)))
        assertEquals(ScheduleResolution.ActiveSchedule(r), ScheduleResolver.resolve(all, mon(9, 0)))
    }

    // ================================================================
    // Rule 2/7 — day and time selection
    // ================================================================

    @Test
    fun rule2_dayMismatchIsInactive() {
        val mondayOnly = rule(1, name = "Monday only", days = ScheduleDays.of(DayOfWeek.MONDAY), start = 8 to 0, end = 18 to 0, priority = 50)

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(mondayOnly), tue(12, 0)))
    }

    @Test
    fun rule7_emptyScheduleListIsNoActiveSchedule() {
        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(emptyList(), mon(12, 0)))
    }

    @Test
    fun rule2_anySelectedDayApplies() {
        val weekdays = ScheduleDays.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        val r = rule(1, name = "Weekdays", days = weekdays, start = 8 to 0, end = 18 to 0, priority = 1)

        assertEquals(r, winner(listOf(r), mon(12, 0)))
        assertEquals(r, winner(listOf(r), tue(12, 0)))
    }

    // ================================================================
    // Regression — Step 1 cross-midnight ownership rules
    // ================================================================

    @Test
    fun regression_mondayCrossMidnightOwnedByMonday() {
        val r = rule(1, name = "Mon night", days = ScheduleDays.of(DayOfWeek.MONDAY), start = 22 to 0, end = 7 to 0, priority = 1)

        assertFalse("Mon 21:59", ScheduleResolver.isActive(r, mon(21, 59)))
        assertTrue("Mon 22:00", ScheduleResolver.isActive(r, mon(22, 0)))
        assertTrue("Mon 23:59", ScheduleResolver.isActive(r, mon(23, 59)))
        assertTrue("Tue 00:00", ScheduleResolver.isActive(r, tue(0, 0)))
        assertTrue("Tue 06:59", ScheduleResolver.isActive(r, tue(6, 59)))
        assertFalse("Tue 07:00", ScheduleResolver.isActive(r, tue(7, 0)))
    }

    @Test
    fun regression_sundayCrossMidnightContinuesIntoMonday() {
        val sunday = LocalDateTime.of(2026, 9, 20, 22, 0)
        val mondayEarly = LocalDateTime.of(2026, 9, 21, 0, 30)
        val mondayLate = LocalDateTime.of(2026, 9, 21, 6, 59)
        val mondayEnd = LocalDateTime.of(2026, 9, 21, 7, 0)
        val r = rule(1, name = "Sun night", days = ScheduleDays.of(DayOfWeek.SUNDAY), start = 22 to 0, end = 7 to 0, priority = 1)

        assertTrue("Sun 22:00", ScheduleResolver.isActive(r, sunday))
        assertTrue("Mon 00:30", ScheduleResolver.isActive(r, mondayEarly))
        assertTrue("Mon 06:59", ScheduleResolver.isActive(r, mondayLate))
        assertFalse("Mon 07:00", ScheduleResolver.isActive(r, mondayEnd))
    }

    @Test
    fun regression_crossMidnightContinuationNeedsOnlyTheStartDay() {
        val r = rule(1, name = "Mon night", days = ScheduleDays.of(DayOfWeek.MONDAY), start = 22 to 0, end = 7 to 0, priority = 1)

        assertFalse("Tuesday is not a selected owning day", r.days.contains(DayOfWeek.TUESDAY))
        assertTrue("but Monday's interval still covers Tuesday 00:30", ScheduleResolver.isActive(r, tue(0, 30)))
    }

    // ================================================================
    // Determinism — repeated resolution is stable
    // ================================================================

    @Test
    fun resolutionIsRepeatableForTheSameInput() {
        val a = rule(1, name = "A", start = 8 to 0, end = 18 to 0, priority = 10)
        val b = rule(2, name = "B", start = 9 to 0, end = 17 to 0, priority = 20)
        val all = listOf(a, b)
        val expected = ScheduleResolver.resolve(all, mon(12, 0))

        repeat(5) {
            assertEquals(expected, ScheduleResolver.resolve(all, mon(12, 0)))
        }
    }

    @Test
    fun conflictPresentationOrderIsByNameThenId() {
        val zulu = rule(7, name = "Zulu", start = 8 to 0, end = 18 to 0, priority = 3)
        val alpha = rule(9, name = "Alpha", start = 8 to 0, end = 18 to 0, priority = 3)

        assertEquals(listOf(alpha, zulu), conflict(listOf(zulu, alpha), mon(12, 0)))
    }

    @Test
    fun conflictPresentationOrderUsesIdWhenNamesTie() {
        val later = rule(9, name = "Same", start = 8 to 0, end = 18 to 0, priority = 3)
        val earlier = rule(4, name = "Same", start = 8 to 0, end = 18 to 0, priority = 3)

        assertEquals(listOf(earlier, later), conflict(listOf(later, earlier), mon(12, 0)))
    }

    // ================================================================
    // LocalDateTime and Instant+ZoneId must agree
    // ================================================================

    @Test
    fun instantResolutionAgreesWithLocalResolution() {
        val zone = ZoneId.of("Asia/Tashkent")
        val p10 = rule(1, name = "P10", start = 8 to 0, end = 18 to 0, priority = 10)
        val p20 = rule(2, name = "P20", start = 9 to 0, end = 17 to 0, priority = 20)
        val all = listOf(p10, p20)

        listOf(mon(12, 0), mon(8, 0), mon(18, 0), tue(12, 0), tue(3, 0)).forEach { local ->
            val viaInstant = ScheduleResolver.resolve(all, local.atZone(zone).toInstant(), zone)
            assertEquals("at $local", ScheduleResolver.resolve(all, local), viaInstant)
        }
    }

    @Test
    fun instantResolutionAgreesForACrossMidnightConflict() {
        val zone = ZoneId.of("Asia/Tashkent")
        val all = listOf(crossA, crossB)

        listOf(mon(23, 30), tue(2, 0), tue(6, 0), tue(6, 30), tue(7, 0)).forEach { local ->
            val viaInstant = ScheduleResolver.resolve(all, local.atZone(zone).toInstant(), zone)
            assertEquals("at $local", ScheduleResolver.resolve(all, local), viaInstant)
        }
    }

    @Test
    fun instantResolutionUsesTheSuppliedZoneNotTheSystemDefault() {
        // 2026-09-21T20:00Z is Monday 20:00 UTC (before Monday's 22:00 start) but Tuesday
        // 01:00 in Tashkent (UTC+5), inside Monday's after-midnight portion.
        val r = rule(1, name = "Mon night", days = ScheduleDays.of(DayOfWeek.MONDAY), start = 22 to 0, end = 7 to 0, priority = 1)
        val instant = Instant.parse("2026-09-21T20:00:00Z")

        assertEquals(ScheduleResolution.NoActiveSchedule, ScheduleResolver.resolve(listOf(r), instant, ZoneId.of("UTC")))
        assertEquals(ScheduleResolution.ActiveSchedule(r), ScheduleResolver.resolve(listOf(r), instant, ZoneId.of("Asia/Tashkent")))
    }

    // ================================================================
    // Priority ordering, including negative and zero
    // ================================================================

    @Test
    fun highestPriorityWinsAcrossNegativeZeroAndPositive() {
        val negative = rule(1, name = "Neg", start = 8 to 0, end = 18 to 0, priority = -5)
        val zero = rule(2, name = "Zero", start = 8 to 0, end = 18 to 0, priority = 0)
        val positive = rule(3, name = "Pos", start = 8 to 0, end = 18 to 0, priority = 5)

        assertEquals(positive, winner(listOf(negative, zero, positive), mon(12, 0)))
        assertEquals(positive, winner(listOf(positive, zero, negative), mon(12, 0)))
    }

    @Test
    fun negativeHighestPriorityStillWinsWhenUnique() {
        val low = rule(1, name = "Low", start = 8 to 0, end = 18 to 0, priority = -10)
        val high = rule(2, name = "High", start = 8 to 0, end = 18 to 0, priority = -1)

        assertEquals(high, winner(listOf(low, high), mon(12, 0)))
    }

    // ================================================================
    // Mixing active and inactive schedules never fabricates a conflict
    // ================================================================

    @Test
    fun onlyActiveSchedulesParticipate() {
        // Three schedule share priority 10 but only two are active at 12:00 Monday.
        val activeMonday = rule(1, name = "Active A", days = ScheduleDays.of(DayOfWeek.MONDAY), start = 8 to 0, end = 18 to 0, priority = 10)
        val activeMonday2 = rule(2, name = "Active B", days = ScheduleDays.of(DayOfWeek.MONDAY), start = 9 to 0, end = 17 to 0, priority = 10)
        val inactiveFriday = rule(3, name = "Friday", days = ScheduleDays.of(DayOfWeek.FRIDAY), start = 8 to 0, end = 18 to 0, priority = 10)

        assertConflict(listOf(activeMonday, activeMonday2, inactiveFriday), mon(12, 0), listOf(activeMonday, activeMonday2))
    }

    @Test
    fun aHigherPriorityOutsideTheOverlapDoesNotAffectTheOverlap() {
        // The 30-priority schedule runs 08:00-09:00, so at 12:00 it is inactive and the
        // overlapping 10/20 schedules decide normally.
        val early = rule(1, name = "Early", start = 8 to 0, end = 9 to 0, priority = 30)
        val p10 = rule(2, name = "P10", start = 11 to 0, end = 13 to 0, priority = 10)
        val p20 = rule(3, name = "P20", start = 12 to 0, end = 14 to 0, priority = 20)

        assertEquals(p20, winner(listOf(early, p10, p20), mon(12, 30)))
    }
}
