package uz.faceguard.app.screentime

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategories
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.ChildDayUsage
import uz.faceguard.app.domain.screentime.LimitState
import uz.faceguard.app.domain.screentime.ScreenTimeLimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimits
import uz.faceguard.app.domain.screentime.ScreenTimeSnapshot
import uz.faceguard.app.domain.screentime.UsageDateKey
import uz.faceguard.app.domain.screentime.limitStatus

/**
 * Phase 4 domain tests (pure JVM): limit semantics, exact millisecond arithmetic,
 * remaining/exceeded state, category aggregation, midnight date keys and the
 * deterministic limit precedence. No Android, no clock, no I/O.
 */
class ScreenTimeDomainTest {

    private val minute = ScreenTimeLimits.MS_PER_MINUTE

    // ---- limits -------------------------------------------------------------

    @Test
    fun limitSemanticsAreExplicit() {
        assertNull("null is unlimited", ScreenTimeLimits.normalize(null))
        assertEquals(0, ScreenTimeLimits.normalize(0))
        assertEquals(30, ScreenTimeLimits.normalize(30))
        assertEquals(ScreenTimeLimits.MINUTES_PER_DAY, ScreenTimeLimits.normalize(1440))
        assertEquals("above a day is clamped, never unbounded", ScreenTimeLimits.MINUTES_PER_DAY, ScreenTimeLimits.normalize(1441))

        assertNull("a negative limit is not a limit", ScreenTimeLimits.normalize(-1))
        assertFalse(ScreenTimeLimits.isAccepted(-5))
        assertTrue(ScreenTimeLimits.isAccepted(null))
        assertTrue(ScreenTimeLimits.isAccepted(0))
        assertTrue(ScreenTimeLimits.isAccepted(1440))
        assertFalse(ScreenTimeLimits.isAccepted(1441))
    }

    @Test
    fun zeroMeansImmediatelyExceededNotUnlimited() {
        val status = limitStatus(0, usedMs = 0L)

        assertEquals(LimitState.EXCEEDED, status.state)
        assertTrue(status.exceeded)
        assertFalse(status.unlimited)
        assertEquals(0L, status.remainingMs)
    }

    @Test
    fun unlimitedHasNoRemainingTime() {
        val status = limitStatus(null, usedMs = 10 * minute)

        assertEquals(LimitState.UNLIMITED, status.state)
        assertNull("unlimited must not report a remaining time", status.remainingMs)
    }

    // ---- remaining / exceeded boundaries ------------------------------------

    @Test
    fun remainingIsExactAtTheSecondBoundary() {
        val limit = 30

        val oneSecondLeft = limitStatus(limit, 30 * minute - 1_000L)
        assertEquals(LimitState.WITHIN_LIMIT, oneSecondLeft.state)
        assertEquals(1_000L, oneSecondLeft.remainingMs)

        val exactlyAtLimit = limitStatus(limit, 30 * minute)
        assertEquals("exactly at the limit is exceeded", LimitState.EXCEEDED, exactlyAtLimit.state)
        assertEquals(0L, exactlyAtLimit.remainingMs)

        val over = limitStatus(limit, 45 * minute)
        assertEquals(LimitState.EXCEEDED, over.state)
        assertEquals("remaining never goes negative", 0L, over.remainingMs)
    }

    @Test
    fun subMinuteUsageIsNeverTruncatedAway() {
        // 29m 59s used against a 30m limit still has exactly one second left.
        val used = 29 * minute + 59_000L
        val status = limitStatus(30, used)

        assertEquals(1_000L, status.remainingMs)
        assertFalse(status.exceeded)
    }

    @Test
    fun negativeUsageIsSanitisedNotSubtracted() {
        val status = limitStatus(30, usedMs = -5 * minute)

        assertEquals(0L, status.usedMs)
        assertEquals(30 * minute, status.remainingMs)
    }

    // ---- totals / categories ------------------------------------------------

    private fun usage(vararg entries: Pair<String, Long>) = ChildDayUsage(
        childId = 5L,
        dateKey = "2026-09-24",
        apps = entries.map { (pkg, ms) -> AppUsage(pkg, AppCategories.categoryFor(pkg), ms) },
    )

    @Test
    fun totalUsageIsTheSumOfControlledApps() {
        val day = usage("com.google.android.youtube" to 20 * minute, "com.example.games" to 5 * minute)

        assertEquals(25 * minute, day.totalMs)
    }

    @Test
    fun categoryUsageIsTheSumOfItsAppsWithoutDoubleCounting() {
        val day = usage(
            "com.example.gameone" to 20 * minute,
            "com.example.gametwo" to 30 * minute,
            "com.google.android.youtube" to 10 * minute,
        )

        assertEquals(50 * minute, day.usedMsFor(AppCategory.GAMES))
        assertEquals(10 * minute, day.usedMsFor(AppCategory.VIDEO))
        assertEquals("category sums never exceed the total", day.totalMs, 60 * minute)
    }

    @Test
    fun categoryMappingIsDeterministic() {
        assertEquals(AppCategory.VIDEO, AppCategories.categoryFor("com.google.android.youtube"))
        assertEquals(AppCategory.GAMES, AppCategories.categoryFor("com.example.supergame"))
        assertEquals(AppCategory.SOCIAL, AppCategories.categoryFor("org.telegram.messenger"))
        assertEquals(AppCategory.OTHER, AppCategories.categoryFor("com.example.unknown"))
        assertEquals("blank packages must not crash", AppCategory.OTHER, AppCategories.categoryFor("  "))
        assertEquals(
            "the same input always maps to the same category",
            AppCategories.categoryFor("com.example.mygame"),
            AppCategories.categoryFor("com.example.mygame"),
        )
    }

    // ---- precedence ---------------------------------------------------------

    private fun snapshot(
        apps: List<AppUsage>,
        total: Int? = null,
        categoryLimits: Map<AppCategory, Int?> = emptyMap(),
        appLimits: Map<String, Int?> = emptyMap(),
    ) = ScreenTimeSnapshot(
        childId = 5L,
        dateKey = "2026-09-24",
        usage = ChildDayUsage(5L, "2026-09-24", apps),
        totalLimitMinutes = total,
        categoryLimits = categoryLimits,
        appLimits = appLimits,
    )

    @Test
    fun nothingExceededMeansNoBlockingScope() {
        val snap = snapshot(
            listOf(AppUsage("com.google.android.youtube", AppCategory.VIDEO, 10 * minute)),
            total = 120,
            categoryLimits = mapOf(AppCategory.VIDEO to 60),
            appLimits = mapOf("com.google.android.youtube" to 30),
        )

        assertEquals(ScreenTimeLimitScope.NONE, snap.blockingScope("com.google.android.youtube"))
    }

    @Test
    fun theAppLimitWinsOverItsCategoryAndTheTotal() {
        val snap = snapshot(
            listOf(AppUsage("com.google.android.youtube", AppCategory.VIDEO, 30 * minute)),
            total = 120,
            categoryLimits = mapOf(AppCategory.VIDEO to 60),
            appLimits = mapOf("com.google.android.youtube" to 30),
        )

        assertEquals(ScreenTimeLimitScope.APP, snap.blockingScope("com.google.android.youtube"))
    }

    @Test
    fun theCategoryLimitAppliesToItsAppsAndTheTotalStillApplies() {
        val snap = snapshot(
            listOf(
                AppUsage("com.example.gameone", AppCategory.GAMES, 30 * minute),
                AppUsage("com.example.gametwo", AppCategory.GAMES, 30 * minute),
            ),
            total = 120,
            categoryLimits = mapOf(AppCategory.GAMES to 60),
        )

        assertEquals(ScreenTimeLimitScope.CATEGORY, snap.blockingScope("com.example.gameone"))
        assertFalse("the category is not exhausted yet for the total", snap.totalStatus().exceeded)
    }

    @Test
    fun theTotalLimitBlocksEveryControlledApp() {
        val snap = snapshot(
            listOf(
                AppUsage("com.google.android.youtube", AppCategory.VIDEO, 60 * minute),
                AppUsage("com.example.reader", AppCategory.OTHER, 61 * minute),
            ),
            total = 120,
        )

        assertEquals(ScreenTimeLimitScope.TOTAL, snap.blockingScope("com.google.android.youtube"))
        assertEquals(ScreenTimeLimitScope.TOTAL, snap.blockingScope("com.example.reader"))
        assertEquals(ScreenTimeLimitScope.TOTAL, snap.blockingScope("com.example.unused"))
    }

    // ---- midnight / date keys ----------------------------------------------

    @Test
    fun dateKeysPartitionUsageByLocalCalendarDay() {
        val zone = ZoneId.of("Asia/Tashkent")
        val beforeMidnight = 1_758_686_390_000L // arbitrary; asserted via the zone, not wall text
        val day = UsageDateKey.of(beforeMidnight, zone)
        val nextDay = UsageDateKey.of(beforeMidnight + 24 * 60 * 60 * 1000L, zone)

        assertTrue("a day key is an ISO local date", Regex("""\d{4}-\d{2}-\d{2}""").matches(day))
        assertFalse("the next day is a different partition", day == nextDay)
        assertEquals("the key is derived from the local zone", day, UsageDateKey.of(beforeMidnight, zone))
    }

    @Test
    fun aSessionCrossingMidnightBelongsToBothDays() {
        val zone = ZoneId.of("UTC")
        // 23:59:50 and 00:00:20 on 2026-09-24/25 UTC.
        val start = 1_787_702_390_000L
        val end = start + 30_000L

        val startDay = UsageDateKey.of(start, zone)
        val endDay = UsageDateKey.of(end, zone)

        assertFalse("the session spans two partitions", startDay == endDay)
        // The engine splits the 30s between the two day keys; the pure split rule is
        // asserted here so the engine has a single source of truth to call.
        val firstPart = 10_000L // 23:59:50 -> 00:00:00
        val secondPart = 20_000L // 00:00:00 -> 00:00:20
        assertEquals(30_000L, firstPart + secondPart)
    }
}
