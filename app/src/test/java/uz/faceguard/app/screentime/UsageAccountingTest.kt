package uz.faceguard.app.screentime

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageAccounting
import uz.faceguard.app.domain.screentime.UsageDelta
import uz.faceguard.app.domain.screentime.UsageInterval

/**
 * Phase 4 Step 1B-3 (pure JVM): turning reported spans into per-day deltas — midnight
 * splitting, overlap/duplicate collapse, and app/category/total aggregation.
 *
 * Every test passes an explicit [ZoneId]; nothing here reads the device zone, so the
 * results are the same anywhere.
 */
class UsageAccountingTest {

    private val tashkent: ZoneId = ZoneId.of("Asia/Tashkent")
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = ScreenTimeUsageRepository.MAX_DELTA_MS

    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"

    private fun at(hourOfDay: Int, minuteOfHour: Int, secondOfMinute: Int = 0, dayOfMonth: Int = 25): Long =
        ZonedDateTime.of(2026, 9, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, 0, tashkent)
            .toInstant()
            .toEpochMilli()

    private fun interval(
        start: Long,
        end: Long,
        pkg: String = youtube,
        category: AppCategory = AppCategory.VIDEO,
    ) = UsageInterval(pkg, category, start, end)

    private fun deltas(vararg intervals: UsageInterval, zone: ZoneId = tashkent) =
        UsageAccounting.deltasFor(intervals.toList(), zone)

    private fun daysOf(list: List<UsageDelta>) = list.map { it.dateKey to it.elapsedMs }

    // ---- midnight / date boundary -------------------------------------------

    @Test
    fun intervalWithinOneDay_staysOnSameDate() {
        val start = at(10, 0)

        assertEquals(
            listOf("2026-09-25" to 5 * minute),
            daysOf(deltas(interval(start, start + 5 * minute))),
        )
    }

    @Test
    fun intervalCrossingMidnight_splitsUsage() {
        // 23:59:30 -> 00:00:30 is 60s, and it is 30s on each of the two days.
        val start = at(23, 59, 30)

        assertEquals(
            listOf("2026-09-25" to 30_000L, "2026-09-26" to 30_000L),
            daysOf(deltas(interval(start, start + minute))),
        )
    }

    @Test
    fun exactMidnightBoundary_isHandledCorrectly() {
        val midnight = at(0, 0, dayOfMonth = 26)

        assertEquals(
            "a span ending exactly at midnight belongs to the day it started in",
            listOf("2026-09-25" to 30_000L),
            daysOf(deltas(interval(midnight - 30_000L, midnight))),
        )
        assertEquals(
            "a span starting exactly at midnight belongs to the new day",
            listOf("2026-09-26" to 30_000L),
            daysOf(deltas(interval(midnight, midnight + 30_000L))),
        )
    }

    @Test
    fun splitOfAFullDayIntervalProducesTwoDaysThatAddUp() {
        val start = at(10, 0)

        val split = deltas(interval(start, start + day))

        assertEquals(
            listOf("2026-09-25" to 14 * hour, "2026-09-26" to 10 * hour),
            daysOf(split),
        )
        assertEquals("nothing may be lost or invented", day, split.sumOf { it.elapsedMs })
        assertTrue(split.all { it.elapsedMs <= day })
    }

    @Test
    fun timezoneBehavior_isDeterministic() {
        val instant = Instant.parse("2026-09-25T20:00:00Z").toEpochMilli()
        val span = interval(instant, instant + minute)

        assertEquals(
            "UTC files it on the 25th",
            listOf("2026-09-25" to minute),
            daysOf(UsageAccounting.deltasFor(listOf(span), ZoneOffset.UTC)),
        )
        assertEquals(
            "Tashkent (UTC+5) is already the 26th",
            listOf("2026-09-26" to minute),
            daysOf(UsageAccounting.deltasFor(listOf(span), tashkent)),
        )
        assertEquals(
            "the same zone always gives the same answer",
            UsageAccounting.deltasFor(listOf(span), tashkent),
            UsageAccounting.deltasFor(listOf(span), tashkent),
        )
    }

    // ---- duplicate / overlap collapse ---------------------------------------

    @Test
    fun deltasFor_deduplicatesTheSameIntervalReportedTwice() {
        val span = interval(at(10, 0), at(10, 5))

        val once = deltas(span)

        assertEquals(once, deltas(span, span))
        assertEquals(once, deltas(span, span, span))
        assertEquals(listOf(5 * minute), once.map { it.elapsedMs })
    }

    @Test
    fun deltasFor_isIndependentOfIntervalOrder() {
        val a = interval(at(10, 0), at(10, 10))
        val b = interval(at(10, 5), at(10, 15))
        val other = interval(at(11, 0), at(11, 1), pkg = tiktok, category = AppCategory.SOCIAL)

        assertEquals(deltas(a, b, other), deltas(other, b, a))
        assertEquals(deltas(a, b, other), deltas(b, other, a))
    }

    @Test
    fun deltasFor_unionsOverlappingIntervalsOfTheSameApp() {
        // 10:00-10:10 and 10:05-10:15 is 15 minutes of real usage, not 20.
        val unioned = deltas(
            interval(at(10, 0), at(10, 10)),
            interval(at(10, 5), at(10, 15)),
        )

        assertEquals(listOf("2026-09-25" to 15 * minute), daysOf(unioned))
    }

    @Test
    fun deltasFor_nestedIntervalDoesNotDoubleCount() {
        val unioned = deltas(
            interval(at(10, 0), at(10, 30)),
            interval(at(10, 10), at(10, 20)),
        )

        assertEquals(listOf("2026-09-25" to 30 * minute), daysOf(unioned))
    }

    @Test
    fun deltasFor_keepsDifferentAppsSeparate() {
        // Overlap across apps must NOT cancel: only the same app is unioned.
        val split = deltas(
            interval(at(10, 0), at(10, 10), pkg = youtube),
            interval(at(10, 5), at(10, 15), pkg = tiktok, category = AppCategory.SOCIAL),
        )

        assertEquals(
            listOf(youtube to 10 * minute, tiktok to 10 * minute),
            split.map { it.packageName to it.elapsedMs },
        )
    }

    @Test
    fun deltasFor_emptyInputProducesNoDeltas() {
        assertEquals(emptyList<UsageDelta>(), UsageAccounting.deltasFor(emptyList(), tashkent))
    }

    @Test
    fun deltasFor_normalizesThePackageName() {
        val padded = interval(at(10, 0), at(10, 1), pkg = "  $youtube  ")

        val result = deltas(padded)

        assertEquals(youtube, result.single().packageName)
        assertEquals(deltas(interval(at(10, 0), at(10, 1))), result)
    }

    @Test
    fun deltasFor_resolvesConflictingCategoriesDeterministically() {
        val asVideo = interval(at(10, 0), at(10, 1), category = AppCategory.VIDEO)
        val asSocial = interval(at(11, 0), at(11, 1), category = AppCategory.SOCIAL)

        assertEquals(AppCategory.VIDEO, deltas(asVideo, asSocial).single().category)
        assertEquals(AppCategory.VIDEO, deltas(asSocial, asVideo).single().category)
        assertEquals("one app never yields two categories", 1, deltas(asVideo, asSocial).size)
    }

    // ---- aggregation --------------------------------------------------------

    @Test
    fun appUsageAggregation() {
        val summary = UsageAccounting.summarize(
            childId = 10L,
            dateKey = "2026-09-25",
            deltas = deltas(
                interval(at(10, 0), at(10, 10), pkg = youtube),
                interval(at(11, 0), at(11, 1), pkg = tiktok, category = AppCategory.SOCIAL),
            ),
        )

        assertEquals(10 * minute, summary.usedMsFor(youtube))
        assertEquals(1 * minute, summary.usedMsFor(tiktok))
        assertEquals(0L, summary.usedMsFor(duolingo))
    }

    @Test
    fun categoryUsageAggregation() {
        val summary = UsageAccounting.summarize(
            childId = 10L,
            dateKey = "2026-09-25",
            deltas = deltas(
                interval(at(10, 0), at(10, 10), pkg = youtube, category = AppCategory.VIDEO),
                interval(at(11, 0), at(11, 5), pkg = tiktok, category = AppCategory.VIDEO),
            ),
        )

        assertEquals(15 * minute, summary.usedMsFor(AppCategory.VIDEO))
        assertEquals(0L, summary.usedMsFor(AppCategory.GAMES))
    }

    @Test
    fun totalUsageAggregation() {
        // 10 + 5 + 20 = 35 minutes across three apps.
        val summary = UsageAccounting.summarize(
            childId = 10L,
            dateKey = "2026-09-25",
            deltas = deltas(
                interval(at(10, 0), at(10, 10), pkg = youtube),
                interval(at(11, 0), at(11, 5), pkg = tiktok, category = AppCategory.SOCIAL),
                interval(at(12, 0), at(12, 20), pkg = duolingo, category = AppCategory.EDUCATION),
            ),
        )

        assertEquals(35 * minute, summary.totalMs)
        assertEquals(3, summary.apps.size)
    }

    @Test
    fun summarize_onlyCountsTheRequestedDay() {
        val crossingDeltas = deltas(interval(at(23, 59, 30), at(23, 59, 30) + minute))

        assertEquals(
            30_000L,
            UsageAccounting.summarize(10L, "2026-09-25", crossingDeltas).totalMs,
        )
        assertEquals(
            30_000L,
            UsageAccounting.summarize(10L, "2026-09-26", crossingDeltas).totalMs,
        )
        assertEquals(
            "a different day has no usage",
            0L,
            UsageAccounting.summarize(10L, "2026-09-27", crossingDeltas).totalMs,
        )
    }

    @Test
    fun summarize_emptyReturnsZeroUsage() {
        val summary = UsageAccounting.summarize(10L, "2026-09-25", emptyList())

        assertEquals(0L, summary.totalMs)
        assertEquals(emptyList<String>(), summary.apps.map { it.packageName })
        assertEquals(10L, summary.childId)
        assertNotEquals(0L, summary.dateKey.length)
    }
}
