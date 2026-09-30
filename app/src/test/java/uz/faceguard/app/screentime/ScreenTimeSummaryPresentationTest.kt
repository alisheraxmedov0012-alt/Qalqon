package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.LimitState
import uz.faceguard.app.domain.screentime.LimitStatus
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluation
import uz.faceguard.app.feature.home.ScreenTimeDurationFormat
import uz.faceguard.app.feature.home.ScreenTimeInfoLabel
import uz.faceguard.app.feature.home.ScreenTimeSummaryStatus
import uz.faceguard.app.feature.home.ScreenTimeSummaryUiState
import uz.faceguard.app.feature.home.toInfoRow

/**
 * Phase 4 Step 2 (pure JVM): the presentation layer's own logic.
 *
 * Two things are checked here and nowhere else: that a row copies the evaluator's facts
 * verbatim (so the screen never recomputes a remaining time or an exceeded flag), and that a
 * row reflects an unlimited scope as *unlimited* rather than as `0 min`.
 */
class ScreenTimeSummaryPresentationTest {

    private val minute = 60_000L

    private fun evaluation(
        scope: LimitScope = LimitScope.TOTAL,
        limitMinutes: Int? = null,
        usedMs: Long = 0L,
        packageName: String? = null,
        category: AppCategory? = null,
        invalidLimitMinutes: Int? = null,
    ): ScreenTimeLimitEvaluation {
        val normalized = if (invalidLimitMinutes != null) null else limitMinutes
        val status = if (normalized == null) {
            LimitStatus(null, usedMs.coerceAtLeast(0L), LimitState.UNLIMITED)
        } else {
            val limitMs = normalized.toLong() * minute
            LimitStatus(
                normalized,
                usedMs,
                if (usedMs >= limitMs) LimitState.EXCEEDED else LimitState.WITHIN_LIMIT,
            )
        }
        return ScreenTimeLimitEvaluation(
            scope = scope,
            status = status,
            packageName = packageName,
            category = category,
            invalidLimitMinutes = invalidLimitMinutes,
        )
    }

    // ---- a row copies the evaluator, it does not recompute ------------------

    @Test
    fun aLimitedTotalRowCarriesTheEvaluatorsNumbers() {
        val row = evaluation(limitMinutes = 120, usedMs = 70 * minute).toInfoRow()

        assertEquals(ScreenTimeInfoLabel.Total, row.label)
        assertEquals(70 * minute, row.usedMs)
        assertEquals(120, row.limitMinutes)
        assertEquals(50 * minute, row.remainingMs)
        assertTrue(row.hasLimit)
        assertFalse(row.exceeded)
    }

    @Test
    fun anUnlimitedRowHasNoLimitAndNoRemainingTime() {
        val row = evaluation(usedMs = 70 * minute).toInfoRow()

        assertEquals(70 * minute, row.usedMs)
        assertNull("no limit is null, never 0", row.limitMinutes)
        assertNull("and neither is a remaining time claimed", row.remainingMs)
        assertFalse(row.hasLimit)
        assertFalse("a missing limit is never exceeded", row.exceeded)
    }

    @Test
    fun anExceededRowClampsRemainingAtZero() {
        val row = evaluation(limitMinutes = 30, usedMs = 35 * minute).toInfoRow()

        assertTrue(row.exceeded)
        assertEquals(0L, row.remainingMs)
        assertEquals(35 * minute, row.usedMs)
    }

    @Test
    fun exactlyAtTheLimitIsExceeded() {
        val row = evaluation(limitMinutes = 30, usedMs = 30 * minute).toInfoRow()

        assertTrue("equality is reached, not still allowed", row.exceeded)
        assertEquals(0L, row.remainingMs)
    }

    @Test
    fun aZeroLimitWithZeroUsageIsExceededNotUnlimited() {
        val row = evaluation(limitMinutes = 0, usedMs = 0L).toInfoRow()

        assertTrue("0 is a real limit", row.hasLimit)
        assertEquals(0, row.limitMinutes)
        assertTrue("and it is reached immediately", row.exceeded)
        assertEquals(0L, row.remainingMs)
    }

    @Test
    fun anInvalidStoredLimitIsFlaggedAndNotPresentedAsALimit() {
        val row = evaluation(usedMs = 10 * minute, invalidLimitMinutes = -30).toInfoRow()

        assertTrue(row.invalidLimit)
        assertFalse("a corrupt limit is not a limit", row.hasLimit)
        assertNull(row.limitMinutes)
        assertEquals("usage is still reported", 10 * minute, row.usedMs)
    }

    @Test
    fun aCategoryRowCarriesItsCategoryAndAnAppRowItsPackage() {
        val categoryRow = evaluation(
            scope = LimitScope.CATEGORY,
            limitMinutes = 45,
            usedMs = 40 * minute,
            category = AppCategory.GAMES,
        ).toInfoRow()
        val appRow = evaluation(
            scope = LimitScope.APP,
            limitMinutes = 30,
            usedMs = 25 * minute,
            packageName = "com.example.youtube",
        ).toInfoRow()

        assertEquals(ScreenTimeInfoLabel.Category(AppCategory.GAMES), categoryRow.label)
        assertEquals(ScreenTimeInfoLabel.App("com.example.youtube"), appRow.label)
        assertEquals(5 * minute, categoryRow.remainingMs)
        assertEquals(5 * minute, appRow.remainingMs)
    }

    // ---- summary state ------------------------------------------------------

    @Test
    fun aLoadingSummaryClaimsNothing() {
        val state = ScreenTimeSummaryUiState()

        assertEquals(ScreenTimeSummaryStatus.LOADING, state.status)
        assertFalse(state.hasData)
        assertNull(state.total)
    }

    @Test
    fun onlyAReadySummaryIsData() {
        val statuses = ScreenTimeSummaryStatus.entries

        assertEquals(6, statuses.size)
        statuses.forEach { status ->
            val state = ScreenTimeSummaryUiState(status = status)
            assertEquals("only READY carries data, not $status", status == ScreenTimeSummaryStatus.READY, state.hasData)
        }
    }

    @Test
    fun theSummaryListsCategoriesTheChildHasUsageOrALimitFor() {
        val state = ScreenTimeSummaryUiState(
            status = ScreenTimeSummaryStatus.READY,
            total = evaluation(limitMinutes = 120, usedMs = 10 * minute).toInfoRow(),
            categories = listOf(
                evaluation(scope = LimitScope.CATEGORY, usedMs = 0L, category = AppCategory.OTHER).toInfoRow(),
                evaluation(scope = LimitScope.CATEGORY, usedMs = 5 * minute, category = AppCategory.VIDEO).toInfoRow(),
                evaluation(
                    scope = LimitScope.CATEGORY,
                    limitMinutes = 30,
                    usedMs = 0L,
                    category = AppCategory.GAMES,
                ).toInfoRow(),
            ),
        )

        // A category with neither usage nor a limit is not interesting to show.
        assertEquals(
            listOf(AppCategory.VIDEO, AppCategory.GAMES),
            state.usedCategories.map { (it.label as ScreenTimeInfoLabel.Category).category },
        )
    }

    // ---- duration formatting -----------------------------------------------

    @Test
    fun durationsConvertFromMillisecondsExactlyOnce() {
        // 90 seconds is 1 minute, never 90 minutes.
        assertEquals(1L, ScreenTimeDurationFormat.minutesOf(90_000L))
        assertEquals(0L, ScreenTimeDurationFormat.minutesOf(59_000L))
        assertEquals(5L, ScreenTimeDurationFormat.minutesOf(5 * minute))
        assertEquals(60L, ScreenTimeDurationFormat.minutesOf(60 * minute))
        assertEquals(90L, ScreenTimeDurationFormat.minutesOf(90 * minute))
        assertEquals(120L, ScreenTimeDurationFormat.minutesOf(120 * minute))
    }

    @Test
    fun durationsSplitIntoHoursAndMinutes() {
        val ninety = ScreenTimeDurationFormat.partsOf(90 * minute)
        assertEquals(1L, ninety.hours)
        assertEquals(30L, ninety.minutes)
        assertFalse(ninety.isMinutesOnly)

        val underAnHour = ScreenTimeDurationFormat.partsOf(30 * minute)
        assertEquals(0L, underAnHour.hours)
        assertEquals(30L, underAnHour.minutes)
        assertTrue(underAnHour.isMinutesOnly)

        val exactlyTwoHours = ScreenTimeDurationFormat.partsOf(120 * minute)
        assertEquals(2L, exactlyTwoHours.hours)
        assertEquals(0L, exactlyTwoHours.minutes)
    }

    @Test
    fun durationsNeverGoNegative() {
        assertEquals(0L, ScreenTimeDurationFormat.minutesOf(-5 * minute))
        assertEquals(0L, ScreenTimeDurationFormat.partsOf(-1L).minutes)
    }

    @Test
    fun oneMinuteAndOneHourAreExact() {
        assertEquals(60_000L, ScreenTimeDurationFormat.MS_PER_MINUTE)
        assertEquals(1L, ScreenTimeDurationFormat.minutesOf(ScreenTimeDurationFormat.MS_PER_MINUTE))
        assertEquals(60L, ScreenTimeDurationFormat.minutesOf(60 * ScreenTimeDurationFormat.MS_PER_MINUTE))
    }
}
