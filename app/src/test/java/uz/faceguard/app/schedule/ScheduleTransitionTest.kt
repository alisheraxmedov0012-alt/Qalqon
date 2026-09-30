package uz.faceguard.app.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleStateIdentity
import uz.faceguard.app.domain.schedule.ScheduleTransitionTracker
import uz.faceguard.app.domain.schedule.ScheduleWindow
import uz.faceguard.app.domain.schedule.effectiveScheduleState
import uz.faceguard.app.domain.schedule.resolveScheduleForPackage
import uz.faceguard.app.domain.schedule.scheduleEventDetail

/**
 * Phase 5 Step 6 (pure JVM): schedule activation/deactivation/conflict transitions and the events
 * they produce.
 *
 * Everything here drives the *production* [ScheduleTransitionTracker] and `effectiveScheduleState`,
 * and the schedule inputs are built through the *production* resolver
 * ([resolveScheduleForPackage] / `ScheduleResolver`) wherever the temporal semantics matter. The
 * transition algorithm is never re-implemented in the test.
 *
 * 2026-09-21 is a Monday and 2026-09-22 a Tuesday.
 */
class ScheduleTransitionTest {

    private val zone = ZoneId.of("Asia/Tashkent")
    private val accountId = 1L
    private val childA = 10L
    private val childB = 11L

    private val youtube = "com.google.android.youtube"
    private val calculator = "com.android.calculator2"

    private fun mon(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 21, hour, minute).atZone(zone).toInstant()

    private fun tue(hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, 22, hour, minute).atZone(zone).toInstant()

    private fun rule(
        id: Long,
        name: String = "Schedule $id",
        priority: Int = 0,
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        mode: ScheduleMode = ScheduleMode.CUSTOM,
        days: ScheduleDays = ScheduleDays.of(DayOfWeek.MONDAY),
        start: Pair<Int, Int> = 8 to 0,
        end: Pair<Int, Int> = 18 to 0,
        enabled: Boolean = true,
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

    /**
     * Records what the engine would persist, using the same shape the engine uses: one log call per
     * non-null transition. The tracker is production code; this only counts its output.
     */
    private class EventLog {
        val events = mutableListOf<Pair<ActivityEventType, String?>>()

        val scheduleEvents: List<Pair<ActivityEventType, String?>>
            get() = events.filter { it.first == ActivityEventType.SCHEDULE_CHANGED }

        fun observe(
            tracker: ScheduleTransitionTracker,
            resolution: ScheduleResolution,
            childId: Long?,
            isProtectedApp: Boolean = true,
        ) {
            val transition = tracker.onResolution(resolution, childId, isProtectedApp) ?: return
            events += ActivityEventType.SCHEDULE_CHANGED to scheduleEventDetail(transition.to)
        }
    }

    /** The resolution the production lookup produces for [childSchedules] inside a protected app. */
    private fun resolutionFor(
        childSchedules: List<ScheduleRule>,
        targets: Map<Long, Set<String>>,
        packageName: String,
        at: Instant,
    ): ScheduleResolution = resolveScheduleForPackage(childSchedules, targets, packageName, at, zone)

    private fun active(resolution: ScheduleResolution): ScheduleRule {
        assertTrue("expected an active schedule but was $resolution", resolution is ScheduleResolution.ActiveSchedule)
        return (resolution as ScheduleResolution.ActiveSchedule).schedule
    }

    private fun activeOf(id: Long) = ScheduleStateIdentity.Active(childA, id)

    // ================================================================
    // TEST 1/2/3/4/5 — the basic transitions
    // ================================================================

    @Test
    fun test1_noScheduleToActiveLogsExactlyOneActivation() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)

        assertEquals(1, log.scheduleEvents.size)
        assertEquals("child 10 / schedule 1", log.scheduleEvents.single().second)
    }

    @Test
    fun test2_activeToTheSameActiveScheduleLogsNothing() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        val same = ScheduleResolution.ActiveSchedule(rule(1))

        log.observe(tracker, same, childA)
        repeat(10) { log.observe(tracker, same, childA) }

        assertEquals("only the first observation is an event", 1, log.scheduleEvents.size)
    }

    @Test
    fun test3_activeToNoScheduleLogsExactlyOneTransition() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)
        log.observe(tracker, ScheduleResolution.NoActiveSchedule, childA)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("none", log.scheduleEvents.last().second)
    }

    @Test
    fun test4_activeAToActiveBLogsExactlyOneTransition() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)
        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(2)), childA)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("child 10 / schedule 2", log.scheduleEvents.last().second)
    }

    @Test
    fun test5_noScheduleToNoScheduleLogsNothing() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        repeat(10) { log.observe(tracker, ScheduleResolution.NoActiveSchedule, childA) }

        assertTrue(log.scheduleEvents.isEmpty())
    }

    // ================================================================
    // TEST 6/7/8/9 — conflicts
    // ================================================================

    @Test
    fun test6_theSameConflictLogsNothing() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        val conflict = ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2)))

        log.observe(tracker, conflict, childA)
        repeat(10) { log.observe(tracker, conflict, childA) }

        assertEquals(1, log.scheduleEvents.size)
    }

    @Test
    fun test7_activeAToConflictABLogsExactlyOneEvent() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)
        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))), childA)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("child 10 / conflict 1+2", log.scheduleEvents.last().second)
    }

    @Test
    fun test8_conflictABToActiveALogsExactlyOneEvent() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))), childA)
        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("child 10 / schedule 1", log.scheduleEvents.last().second)
    }

    @Test
    fun test9_conflictToTheSameConflictLogsNothing() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))), childA)
        repeat(5) { log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(2), rule(1))), childA) }

        assertEquals("the same tied set must not re-log", 1, log.scheduleEvents.size)
    }

    @Test
    fun test10_conflictOrderIsIrrelevantToIdentity() {
        val forward = ScheduleStateIdentity.of(ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))), childA)
        val reversed = ScheduleStateIdentity.of(ScheduleResolution.ScheduleConflict(listOf(rule(2), rule(1))), childA)

        assertEquals(forward, reversed)
        assertEquals(ScheduleStateIdentity.Conflict(childA, listOf(1L, 2L)), forward)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))), childA)
        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(2), rule(1))), childA)

        assertEquals(1, log.scheduleEvents.size)
    }

    @Test
    fun test_changingConflictMembershipIsATransition() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))), childA)
        log.observe(tracker, ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2), rule(3))), childA)

        assertEquals(2, log.scheduleEvents.size)
    }

    // ================================================================
    // TEST 11/12 — identity is the persisted id
    // ================================================================

    @Test
    fun test11_theSameIdWithChangedFieldsIsNotATransition() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1, name = "Before", priority = 1)), childA)
        // Edited while still active: same logical schedule, so no invented transition.
        log.observe(
            tracker,
            ScheduleResolution.ActiveSchedule(
                rule(1, name = "After", priority = 99, start = 9 to 0, end = 17 to 0),
            ),
            childA,
        )

        assertEquals(1, log.scheduleEvents.size)
    }

    @Test
    fun test12_differentIdsAreATransitionEvenWithTheSameName() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1, name = "Bedtime")), childA)
        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(2, name = "Bedtime")), childA)

        assertEquals(2, log.scheduleEvents.size)
    }

    @Test
    fun identityDistinguishesActiveFromConflictWithTheSameSingleSchedule() {
        assertFalse(
            ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1)), childA) ==
                ScheduleStateIdentity.of(ScheduleResolution.ScheduleConflict(listOf(rule(1))), childA),
        )
    }

    // ================================================================
    // TEST 13/14 — child and account scoping
    // ================================================================

    @Test
    fun test13_childrenHaveIndependentScheduleStates() {
        val childAState = ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1)), childA)
        val childBState = ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1)), childB)

        assertFalse("the same schedule id under another child is a different state", childAState == childBState)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)
        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childB)

        // Child B's activation is its own event, not suppressed by child A's identical state.
        assertEquals(2, log.scheduleEvents.size)
        assertEquals("child 11 / schedule 1", log.scheduleEvents.last().second)
    }

    @Test
    fun test14_anAccountResetDropsTheStateSoTheNextObservationIsEvaluatedAfresh() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        val state = ScheduleResolution.ActiveSchedule(rule(1))

        log.observe(tracker, state, childA)
        assertEquals(1, log.scheduleEvents.size)

        // Account switch: the runtime resets the engine's schedule state, so a previous account's
        // schedule cannot linger as this session's baseline.
        tracker.reset()
        assertEquals(ScheduleStateIdentity.None, tracker.current)

        // The first observation of the new session is a real activation.
        log.observe(tracker, state, childA)
        assertEquals(2, log.scheduleEvents.size)
    }

    @Test
    fun test_noEventForAnAccountThatNeverHadASchedule() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        repeat(5) { log.observe(tracker, ScheduleResolution.NoActiveSchedule, null) }

        assertTrue(log.scheduleEvents.isEmpty())
    }

    // ================================================================
    // TEST 15/16/17 — parent / unknown / no-face
    // ================================================================

    @Test
    fun test15_aParentIsNeverReportedAsUnderAChildSchedule() {
        val activeSchedule = ScheduleResolution.ActiveSchedule(rule(1))

        // No child scope: the same active schedule is not effective for a parent.
        assertEquals(
            ScheduleStateIdentity.None,
            ScheduleStateIdentity.of(activeSchedule, childId = null),
        )

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, activeSchedule, childA)          // child, schedule effective
        log.observe(tracker, activeSchedule, null)            // parent recognised: no longer effective

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("none", log.scheduleEvents.last().second)
    }

    @Test
    fun test16_anUnknownUserDoesNotActivateAChildSchedule() {
        assertEquals(
            ScheduleStateIdentity.None,
            ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1)), childId = null),
        )
    }

    @Test
    fun test17_aNoFaceObservationDoesNotActivateAChildSchedule() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        repeat(5) { log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childId = null) }

        assertTrue(log.scheduleEvents.isEmpty())
        assertEquals(ScheduleStateIdentity.None, tracker.current)
    }

    // ================================================================
    // TEST 18/19/20 — the protected-app gate and target membership
    // ================================================================

    @Test
    fun test18_anUnprotectedPackageNeverActivatesASchedule() {
        val activeSchedule = ScheduleResolution.ActiveSchedule(rule(1))

        // isProtectedApp = false is the Step 4 rule no schedule may change.
        assertEquals(
            ScheduleStateIdentity.None,
            effectiveScheduleState(activeSchedule, childId = childA, isProtectedApp = false),
        )

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        repeat(5) { log.observe(tracker, activeSchedule, childA, isProtectedApp = false) }

        assertTrue("an unprotected app must not log a schedule activation", log.scheduleEvents.isEmpty())
        assertEquals(ScheduleStateIdentity.None, tracker.current)
    }

    @Test
    fun test19_aProtectedPackageWithAMatchingTargetActivatesTheSchedule() {
        val schedule = rule(1)
        val resolution = resolutionFor(
            childSchedules = listOf(schedule),
            targets = mapOf(1L to setOf(youtube)),
            packageName = youtube,
            at = mon(12, 0),
        )

        assertEquals(schedule, active(resolution))

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, resolution, childA, isProtectedApp = true)

        assertEquals(1, log.scheduleEvents.size)
    }

    @Test
    fun test20_aTargetPackageMismatchProducesNoActiveSchedule() {
        val schedule = rule(1)
        val resolution = resolutionFor(
            childSchedules = listOf(schedule),
            targets = mapOf(1L to setOf(youtube)),
            packageName = calculator,
            at = mon(12, 0),
        )

        assertEquals(ScheduleResolution.NoActiveSchedule, resolution)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, resolution, childA, isProtectedApp = true)

        assertTrue(log.scheduleEvents.isEmpty())
    }

    @Test
    fun test_aScheduleWithNoTargetsProducesNoActiveSchedule() {
        val resolution = resolutionFor(
            childSchedules = listOf(rule(1)),
            targets = emptyMap(),
            packageName = youtube,
            at = mon(12, 0),
        )

        assertEquals(ScheduleResolution.NoActiveSchedule, resolution)
    }

    // ================================================================
    // TEST 21 — cross-midnight follows the existing resolver
    // ================================================================

    @Test
    fun test21_crossMidnightActivationAndDeactivationFollowTheResolver() {
        val overnight = rule(
            1,
            days = ScheduleDays.of(DayOfWeek.MONDAY),
            start = 22 to 0,
            end = 7 to 0,
        )
        val targets = mapOf(1L to setOf(youtube))

        fun at(hour: Int, minute: Int, tuesday: Boolean = false) = resolutionFor(
            childSchedules = listOf(overnight),
            targets = targets,
            packageName = youtube,
            at = if (tuesday) tue(hour, minute) else mon(hour, minute),
        )

        assertEquals(ScheduleResolution.NoActiveSchedule, at(21, 59))
        assertEquals(overnight, active(at(22, 0)))
        assertEquals(overnight, active(at(23, 59)))
        assertEquals(overnight, active(at(0, 0, tuesday = true)))
        assertEquals(overnight, active(at(6, 59, tuesday = true)))
        assertEquals(ScheduleResolution.NoActiveSchedule, at(7, 0, tuesday = true))

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        listOf(21 to 59, 22 to 0, 23 to 59).forEach { (h, m) -> log.observe(tracker, at(h, m), childA) }
        listOf(0 to 0, 6 to 59, 7 to 0).forEach { (h, m) -> log.observe(tracker, at(h, m, tuesday = true), childA) }

        // Inactive -> active -> (unchanged) -> (unchanged) -> (unchanged) -> inactive: two events.
        assertEquals(2, log.scheduleEvents.size)
    }

    // ================================================================
    // TEST 22/23/24 — priority, conflict, resolution through the resolver
    // ================================================================

    @Test
    fun test22_aHigherPriorityWinnerBecomingActiveIsOneTransition() {
        val low = rule(1, priority = 10, start = 8 to 0, end = 18 to 0)
        val high = rule(2, priority = 20, start = 9 to 0, end = 17 to 0)
        val formats = mapOf(1L to setOf(youtube), 2L to setOf(youtube))
        val schedules = listOf(low, high)

        // 08:30 -> only the low schedule is active; 09:30 -> the higher one wins.
        val early = resolutionFor(schedules, formats, youtube, mon(8, 30))
        val later = resolutionFor(schedules, formats, youtube, mon(9, 30))
        assertEquals(low, active(early))
        assertEquals(high, active(later))

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, early, childA)
        log.observe(tracker, later, childA)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("child 10 / schedule 2", log.scheduleEvents.last().second)
    }

    @Test
    fun test23_anEqualPriorityConflictIsOneTransition() {
        val a = rule(1, priority = 20)
        val b = rule(2, priority = 20)
        val targets = mapOf(1L to setOf(youtube), 2L to setOf(youtube))

        val resolution = resolutionFor(listOf(a, b), targets, youtube, mon(12, 0))
        assertTrue(resolution is ScheduleResolution.ScheduleConflict)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, resolution, childA)
        repeat(5) { log.observe(tracker, resolution, childA) }

        assertEquals(1, log.scheduleEvents.size)
    }

    @Test
    fun test24_aConflictResolvingBackToOneScheduleIsOneTransition() {
        val targets = mapOf(1L to setOf(youtube), 2L to setOf(youtube))
        val tieAt12 = listOf(rule(1, priority = 20), rule(2, priority = 20))
        val onlyOneAt13 = listOf(
            rule(1, priority = 20, start = 8 to 0, end = 18 to 0),
            rule(2, priority = 20, start = 8 to 0, end = 12 to 30),
        )

        val conflict = resolutionFor(tieAt12, targets, youtube, mon(12, 0))
        val single = resolutionFor(onlyOneAt13, targets, youtube, mon(13, 0))

        assertTrue(conflict is ScheduleResolution.ScheduleConflict)
        assertEquals(1L, active(single).id)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, conflict, childA)
        log.observe(tracker, single, childA)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("child 10 / schedule 1", log.scheduleEvents.last().second)
    }

    // ================================================================
    // TEST 25 — a long active schedule produces exactly one event
    // ================================================================

    @Test
    fun test25_repeatedTicksWhileActiveProduceExactlyOneEvent() {
        val schedule = rule(1, start = 8 to 0, end = 18 to 0)
        val targets = mapOf(1L to setOf(youtube))
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        // Simulate the ~500ms tick across a 9-hour active window: 9h / 0.5s = 64_800 evaluations.
        var emitted = 0
        for (tick in 0 until 64_800) {
            val minute = 8 * 60 + (tick / 120)
            val resolution = resolutionFor(
                childSchedules = listOf(schedule),
                targets = targets,
                packageName = youtube,
                at = mon(minute / 60, minute % 60),
            )
            assertTrue(resolution is ScheduleResolution.ActiveSchedule)
            val before = log.scheduleEvents.size
            log.observe(tracker, resolution, childA)
            if (log.scheduleEvents.size != before) emitted++
        }

        assertEquals("a schedule that stays active logs once, not once per tick", 1, emitted)
        assertEquals(1, log.scheduleEvents.size)
    }

    // ================================================================
    // TEST 26 — a foreground change is a real transition
    // ================================================================

    @Test
    fun test26_aForegroundPackageChangeCreatesARealTransition() {
        val schedule = rule(1)
        val targets = mapOf(1L to setOf(youtube))
        val schedules = listOf(schedule)

        val onYoutube = resolutionFor(schedules, targets, youtube, mon(12, 0))
        // A package no schedule targets: the effective state becomes none.
        val onCalculator = resolutionFor(schedules, targets, calculator, mon(12, 0))

        assertEquals(schedule, active(onYoutube))
        assertEquals(ScheduleResolution.NoActiveSchedule, onCalculator)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, onYoutube, childA)
        log.observe(tracker, onCalculator, childA)
        log.observe(tracker, onYoutube, childA)

        assertEquals(3, log.scheduleEvents.size)
        assertEquals("none", log.scheduleEvents[1].second)
    }

    @Test
    fun test26_leavingAProtectedAppIsADeactivation() {
        val schedule = rule(1)
        val targets = mapOf(1L to setOf(youtube))
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, resolutionFor(listOf(schedule), targets, youtube, mon(12, 0)), childA, isProtectedApp = true)
        // Foreground left the protected set entirely: the engine reports no active schedule.
        log.observe(tracker, ScheduleResolution.NoActiveSchedule, childId = null, isProtectedApp = false)

        assertEquals(2, log.scheduleEvents.size)
        assertEquals("none", log.scheduleEvents.last().second)
    }

    // ================================================================
    // TEST 27 — restart semantics
    // ================================================================

    @Test
    fun test27_aFreshEngineTreatsTheFirstActiveObservationAsARealActivation() {
        // A restarted engine holds no baseline, and "no schedule effective" is the honest initial
        // state, so the first observation of an active schedule is a genuine activation.
        val tracker = ScheduleTransitionTracker()
        assertEquals(ScheduleStateIdentity.None, tracker.current)

        val log = EventLog()
        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)

        assertEquals(1, log.scheduleEvents.size)
    }

    @Test
    fun test27_aRestartWithNothingActiveDoesNotFabricateAnEvent() {
        val tracker = ScheduleTransitionTracker()
        val log = EventLog()

        repeat(5) { log.observe(tracker, ScheduleResolution.NoActiveSchedule, childA) }

        assertTrue(log.scheduleEvents.isEmpty())
    }

    // ================================================================
    // TEST 28/29 — disabled schedules
    // ================================================================

    @Test
    fun test28_aDisabledScheduleNeverActivates() {
        val disabled = rule(1, enabled = false)
        val resolution = resolutionFor(
            childSchedules = listOf(disabled),
            targets = mapOf(1L to setOf(youtube)),
            packageName = youtube,
            at = mon(12, 0),
        )

        assertEquals(ScheduleResolution.NoActiveSchedule, resolution)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, resolution, childA)

        assertTrue(log.scheduleEvents.isEmpty())
    }

    @Test
    fun test29_becomingDisabledWhileActiveIsExactlyOneTransitionAway() {
        val enabled = rule(1)
        val targets = mapOf(1L to setOf(youtube))

        val activeBefore = resolutionFor(listOf(enabled), targets, youtube, mon(12, 0))
        val disabledAfter = resolutionFor(listOf(enabled.copy(enabled = false)), targets, youtube, mon(12, 0))

        assertEquals(enabled, active(activeBefore))
        assertEquals(ScheduleResolution.NoActiveSchedule, disabledAfter)

        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        log.observe(tracker, activeBefore, childA)
        log.observe(tracker, disabledAfter, childA)
        // Re-enabling is another single transition back, not a stream.
        repeat(5) { log.observe(tracker, activeBefore, childA) }

        assertEquals(3, log.scheduleEvents.size)
    }

    @Test
    fun test29_aDisabledScheduleIsStillVisibleInTheListButLogsNothing() {
        // Disabling deactivates; it must not delete or re-log. Consecutive ticks on the disabled
        // resolution stay silent.
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        val none = ScheduleResolution.NoActiveSchedule

        repeat(20) { log.observe(tracker, none, childA) }

        assertTrue(log.scheduleEvents.isEmpty())
    }

    // ================================================================
    // TEST 30 — protection disabled
    // ================================================================

    @Test
    fun test30_stoppingProtectionLogsNoScheduleEvent() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()

        log.observe(tracker, ScheduleResolution.ActiveSchedule(rule(1)), childA)
        assertEquals(1, log.scheduleEvents.size)

        // `ProtectionEngine.stop()` resets the state without observing a new one, so disabling
        // protection cannot produce a fake schedule event.
        tracker.reset()

        assertEquals(1, log.scheduleEvents.size)
        assertEquals(ScheduleStateIdentity.None, tracker.current)
    }

    // ================================================================
    // TEST 31/32 — event content and a single event system
    // ================================================================

    @Test
    fun test31_eventDetailNamesTheChildAndSchedule() {
        assertEquals(
            "child 10 / schedule 7",
            scheduleEventDetail(ScheduleStateIdentity.Active(childA, 7L)),
        )
        assertEquals(
            "child 10 / conflict 3+9",
            scheduleEventDetail(ScheduleStateIdentity.Conflict(childA, listOf(3L, 9L))),
        )
        assertEquals("none", scheduleEventDetail(ScheduleStateIdentity.None))

        assertNotNull(scheduleEventDetail(ScheduleStateIdentity.Active(childA, 1L)))
    }

    @Test
    fun test32_theScheduleTransitionUsesTheExistingActivityEventType() {
        // Exactly one schedule-related type, added to the existing enum, so the existing activity
        // log / repository / DAO infrastructure carries it with no second event system.
        val scheduleTypes = ActivityEventType.entries.filter { "SCHEDULE" in it.name }
        assertEquals(listOf(ActivityEventType.SCHEDULE_CHANGED), scheduleTypes)
    }

    @Test
    fun test32_theExistingActivityEventTypesAreUnchanged() {
        assertEquals(
            listOf(
                "CHILD_RECOGNIZED",
                "PARENT_RECOGNIZED",
                "UNKNOWN_USER",
                "NO_FACE",
                "PROTECTED_APP_ENTERED",
                "CHILD_BLOCKED",
                "PROTECTION_RELEASED",
                "PARENT_UNLOCKED",
                "EMERGENCY_UNLOCK",
                "SCHEDULE_CHANGED",
            ),
            ActivityEventType.entries.map { it.name },
        )
    }

    // ================================================================
    // Ordering / equality invariants behind the whole design
    // ================================================================

    @Test
    fun activeIdentityEqualRegardlessOfRuleEquality() {
        // Two distinct instances describing the same id are the same state.
        val a = ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1, name = "A")), childA)
        val b = ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1, name = "B")), childA)

        assertEquals(a, b)
    }

    @Test
    fun aConflictIsDifferentFromAnActiveScheduleEvenWithTheSameMembers() {
        val conflict = ScheduleStateIdentity.of(ScheduleResolution.ScheduleConflict(listOf(rule(1))), childA)
        val single = ScheduleStateIdentity.of(ScheduleResolution.ActiveSchedule(rule(1)), childA)

        assertFalse(conflict == single)
    }

    @Test
    fun aResolutionWithoutAChildIsNeverAnActiveState() {
        listOf(
            ScheduleResolution.ActiveSchedule(rule(1)),
            ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))),
        ).forEach { resolution ->
            assertEquals(ScheduleStateIdentity.None, ScheduleStateIdentity.of(resolution, childId = null))
        }
    }

    @Test
    fun anEmptyConflictCollapsesToNone() {
        assertEquals(
            ScheduleStateIdentity.None,
            ScheduleStateIdentity.of(ScheduleResolution.ScheduleConflict(emptyList()), childA),
        )
    }

    @Test
    fun transitionsAreReportedWhileTheStateKeepsChanging() {
        val log = EventLog()
        val tracker = ScheduleTransitionTracker()
        val seq = listOf(
            ScheduleResolution.NoActiveSchedule,
            ScheduleResolution.ActiveSchedule(rule(1)),
            ScheduleResolution.ActiveSchedule(rule(1)),
            ScheduleResolution.ScheduleConflict(listOf(rule(1), rule(2))),
            ScheduleResolution.ActiveSchedule(rule(2)),
            ScheduleResolution.NoActiveSchedule,
            ScheduleResolution.NoActiveSchedule,
        )

        seq.forEach { log.observe(tracker, it, childA) }

        // none->A (1), A->A (none), A->conflict (2), conflict->B (3), B->none (4), none->none (none)
        assertEquals(4, log.scheduleEvents.size)
        assertEquals(
            listOf(
                "child 10 / schedule 1",
                "child 10 / conflict 1+2",
                "child 10 / schedule 2",
                "none",
            ),
            log.scheduleEvents.map { it.second },
        )
        log.scheduleEvents.forEach { assertEquals(ActivityEventType.SCHEDULE_CHANGED, it.first) }
    }

    @Test
    fun trackerCurrentTracksTheLastObservedState() {
        val tracker = ScheduleTransitionTracker()
        val active = ScheduleResolution.ActiveSchedule(rule(5))

        tracker.onResolution(active, childA, isProtectedApp = true)

        assertEquals(activeOf(5L), tracker.current)
        assertNull(tracker.onResolution(active, childA, isProtectedApp = true))
    }
}
