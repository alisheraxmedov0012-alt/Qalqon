package uz.faceguard.app.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.feature.activity.ActivityChildUsage
import uz.faceguard.app.feature.activity.ActivityDayKind
import uz.faceguard.app.feature.activity.ActivityDayUsage
import uz.faceguard.app.feature.activity.ActivityMetricKind
import uz.faceguard.app.feature.activity.ActivityMetricValue
import uz.faceguard.app.feature.activity.activityDayKind
import uz.faceguard.app.feature.activity.activityDayLabelRes
import uz.faceguard.app.feature.activity.activityMetrics
import uz.faceguard.app.feature.activity.activityScreenTimeTodayMs
import uz.faceguard.app.feature.activity.activityUsageDays
import uz.faceguard.app.feature.activity.eventsTodayCount

/**
 * UI/UX redesign Phase 5: the Activity centre's presentation mapping.
 *
 * Pure JVM — the overview metrics, day grouping, child filter and event counting are
 * pinned without a device. Every state is exercised from real data shapes.
 */
class ActivityPresentationTest {

    private val today = "2026-10-01"
    private val yesterday = "2026-09-30"

    // ------------------------------------------------------------- metrics

    @Test
    fun screenTimeIsUnavailableWhenItCannotBeReadAndNeverZero() {
        val metric = activityMetrics(screenTimeTodayMs = null, pendingRequestCount = 0, eventsToday = 0)
            .single { it.kind == ActivityMetricKind.SCREEN_TIME }
        assertEquals(ActivityMetricValue.Unavailable, metric.value)
    }

    @Test
    fun aGenuineZeroIsShownAsADurationNotUnavailable() {
        val metric = activityMetrics(screenTimeTodayMs = 0L, pendingRequestCount = 0, eventsToday = 0)
            .single { it.kind == ActivityMetricKind.SCREEN_TIME }
        assertEquals(ActivityMetricValue.DurationMs(0L), metric.value)
    }

    @Test
    fun theOverviewCarriesExactlyThreeMetrics() {
        val metrics = activityMetrics(90 * 60_000L, pendingRequestCount = 2, eventsToday = 5)
        assertEquals(
            listOf(
                ActivityMetricKind.SCREEN_TIME,
                ActivityMetricKind.PENDING_REQUESTS,
                ActivityMetricKind.EVENTS_TODAY,
            ),
            metrics.map { it.kind },
        )
        assertEquals(ActivityMetricValue.Count(2), metrics[1].value)
        assertEquals(ActivityMetricValue.Count(5), metrics[2].value)
    }

    @Test
    fun countsAreRealEvenWhenZero() {
        val metrics = activityMetrics(0L, pendingRequestCount = 0, eventsToday = 0)
        assertEquals(ActivityMetricValue.Count(0), metrics[1].value)
        assertEquals(ActivityMetricValue.Count(0), metrics[2].value)
    }

    // ---------------------------------------------------------- day labels

    @Test
    fun todayAndYesterdayHaveNamedLabelsAndEarlierDaysDoNot() {
        assertEquals(ActivityDayKind.TODAY, activityDayKind(today, today, yesterday))
        assertEquals(ActivityDayKind.YESTERDAY, activityDayKind(yesterday, today, yesterday))
        assertEquals(ActivityDayKind.EARLIER, activityDayKind("2026-09-29", today, yesterday))

        assertTrue(activityDayLabelRes(ActivityDayKind.TODAY) != 0)
        assertTrue(activityDayLabelRes(ActivityDayKind.YESTERDAY) != 0)
        assertNull("an earlier day falls back to its ISO date", activityDayLabelRes(ActivityDayKind.EARLIER))
    }

    // ------------------------------------------------------- usage grouping

    @Test
    fun usageDaysAreNewestFirstWithTodayAndYesterdayNamed() {
        val days = activityUsageDays(
            usages = listOf(
                childUsage(1, "Ali", todayMs = 90 * 60_000L, past = listOf(ActivityDayUsage(yesterday, 30 * 60_000L))),
            ),
            selectedChildId = null,
            todayKey = today,
            yesterdayKey = yesterday,
        )
        assertEquals(listOf(today, yesterday), days.map { it.dateKey })
        assertEquals(listOf(ActivityDayKind.TODAY, ActivityDayKind.YESTERDAY), days.map { it.kind })
    }

    @Test
    fun theChildFilterNarrowsTheHistoryToThatChild() {
        val usages = listOf(
            childUsage(1, "Ali", todayMs = 60 * 60_000L),
            childUsage(2, "Vali", todayMs = 30 * 60_000L),
        )
        val all = activityUsageDays(usages, selectedChildId = null, todayKey = today, yesterdayKey = yesterday)
        assertEquals(2, all.single().rows.size)

        val aliOnly = activityUsageDays(usages, selectedChildId = 1L, todayKey = today, yesterdayKey = yesterday)
        assertEquals(listOf("Ali"), aliOnly.single().rows.map { it.name })
    }

    @Test
    fun aDayWithNoRecordedUsageIsOmittedRatherThanPaddedWithZero() {
        val days = activityUsageDays(
            usages = listOf(childUsage(1, "Ali", todayMs = 0L, past = listOf(ActivityDayUsage(yesterday, 0L)))),
            selectedChildId = null,
            todayKey = today,
            yesterdayKey = yesterday,
        )
        assertTrue("a zero-usage day must not appear as a fake 0 row", days.isEmpty())
    }

    @Test
    fun anUnavailableUsageChildProducesNoHistory() {
        val days = activityUsageDays(
            usages = listOf(ActivityChildUsage(1, "Ali", "A", todayMs = null, pastDays = emptyList())),
            selectedChildId = null,
            todayKey = today,
            yesterdayKey = yesterday,
        )
        assertTrue(days.isEmpty())
    }

    @Test
    fun aFilteredChildWithNoUsageYieldsNoDays() {
        val days = activityUsageDays(
            usages = listOf(childUsage(1, "Ali", todayMs = 60 * 60_000L)),
            selectedChildId = 2L,
            todayKey = today,
            yesterdayKey = yesterday,
        )
        assertTrue(days.isEmpty())
    }

    // -------------------------------------------------------- today's total

    @Test
    fun todaysTotalSumsEveryChildWhenUsageIsAvailable() {
        val usages = listOf(
            childUsage(1, "Ali", todayMs = 60 * 60_000L),
            childUsage(2, "Vali", todayMs = 30 * 60_000L),
        )
        assertEquals(90 * 60_000L, activityScreenTimeTodayMs(usages))
    }

    @Test
    fun todaysTotalIsUnavailableIfAnyChildsUsageCouldNotBeRead() {
        val usages = listOf(
            childUsage(1, "Ali", todayMs = 60 * 60_000L),
            ActivityChildUsage(2, "Vali", "V", todayMs = null, pastDays = emptyList()),
        )
        assertNull(activityScreenTimeTodayMs(usages))
    }

    @Test
    fun todaysTotalIsUnavailableWithNoChildren() {
        assertNull(activityScreenTimeTodayMs(emptyList()))
    }

    // ------------------------------------------------------- event counting

    @Test
    fun eventsTodayCountsOnlyTheEventsOnTheGivenDay() {
        val events = listOf(
            event(ActivityEventType.CHILD_BLOCKED, at = 1_000L),
            event(ActivityEventType.PARENT_UNLOCKED, at = 2_000L),
            event(ActivityEventType.NO_FACE, at = 3_000L),
        )
        val count = eventsTodayCount(events, todayKey = today) { at -> if (at <= 2_000L) today else yesterday }
        assertEquals(2, count)
    }

    @Test
    fun eventsTodayIsZeroWhenNothingHappenedOnThatDay() {
        val events = listOf(event(ActivityEventType.CHILD_BLOCKED, at = 1_000L))
        assertEquals(0, eventsTodayCount(events, todayKey = today) { yesterday })
    }

    // ------------------------------------------------------------- helpers

    private fun childUsage(
        id: Long,
        name: String,
        todayMs: Long?,
        past: List<ActivityDayUsage> = emptyList(),
    ) = ActivityChildUsage(id, name, name.first().uppercase(), todayMs, past)

    private fun event(type: ActivityEventType, at: Long) = ActivityEvent(
        id = at,
        accountId = 1L,
        type = type,
        detail = null,
        at = at,
    )
}
