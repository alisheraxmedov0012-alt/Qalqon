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
import uz.faceguard.app.feature.home.ScreenTimeInfoLabel
import uz.faceguard.app.feature.policy.ScreenTimeAppsUiState
import uz.faceguard.app.feature.policy.toAppRow

/**
 * Phase 4 Step 3 (pure JVM): the per-app presentation rules.
 *
 * These check the two things that must not go wrong on the app rows: that a row copies the
 * evaluator (never deriving a remaining time or an exceeded flag itself), and that a row only
 * ever comes from an *app* evaluation — an app must never be shown a limit that belongs to the
 * total or to a category.
 */
class ScreenTimeAppPresentationTest {

    private val minute = 60_000L
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    private fun appEvaluation(
        packageName: String = youtube,
        limitMinutes: Int? = null,
        usedMs: Long = 0L,
        invalidLimitMinutes: Int? = null,
        scope: LimitScope = LimitScope.APP,
        category: AppCategory? = null,
    ): ScreenTimeLimitEvaluation {
        val normalized = if (invalidLimitMinutes != null) null else limitMinutes
        val status = if (normalized == null) {
            LimitStatus(null, usedMs.coerceAtLeast(0L), LimitState.UNLIMITED)
        } else {
            LimitStatus(
                normalized,
                usedMs,
                if (usedMs >= normalized.toLong() * minute) LimitState.EXCEEDED else LimitState.WITHIN_LIMIT,
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

    // ---- the row reflects the evaluator --------------------------------------

    @Test
    fun aLimitedAppReportsItsRemainingTime() {
        val row = appEvaluation(limitMinutes = 30, usedMs = 10 * minute).toAppRow()!!

        assertEquals(ScreenTimeInfoLabel.App(youtube), row.label)
        assertEquals(10 * minute, row.usedMs)
        assertEquals(30, row.limitMinutes)
        assertEquals("10 of 30 leaves 20", 20 * minute, row.remainingMs)
        assertFalse(row.exceeded)
    }

    @Test
    fun exactlyAtTheAppLimitCountsAsReached() {
        val row = appEvaluation(limitMinutes = 30, usedMs = 30 * minute).toAppRow()!!

        assertTrue("equality is reached, not still allowed", row.exceeded)
        assertEquals(0L, row.remainingMs)
    }

    @Test
    fun beyondTheAppLimitIsReachedWithNoNegativeRemaining() {
        val row = appEvaluation(limitMinutes = 30, usedMs = 35 * minute).toAppRow()!!

        assertTrue(row.exceeded)
        assertEquals("remaining clamps to zero", 0L, row.remainingMs)
        assertEquals(35 * minute, row.usedMs)
    }

    @Test
    fun anAppWithoutALimitHasNoLimitAndNoRemaining() {
        // This is the `LIMIT + null` and `ALLOW` shape: unlimited daily time, not zero.
        val row = appEvaluation(limitMinutes = null, usedMs = 25 * minute).toAppRow()!!

        assertNull(row.limitMinutes)
        assertNull("no numeric remaining is claimed", row.remainingMs)
        assertFalse(row.hasLimit)
        assertFalse(row.exceeded)
        assertEquals(25 * minute, row.usedMs)
    }

    @Test
    fun aZeroAppLimitIsReachedImmediately() {
        val row = appEvaluation(limitMinutes = 0, usedMs = 0L).toAppRow()!!

        assertTrue(row.hasLimit)
        assertEquals(0, row.limitMinutes)
        assertTrue("a real configured zero is reached at once", row.exceeded)
    }

    @Test
    fun zeroUsageIsArealValueNotAnAbsence() {
        val row = appEvaluation(limitMinutes = 30, usedMs = 0L).toAppRow()!!

        assertEquals(0L, row.usedMs)
        assertEquals(30 * minute, row.remainingMs)
        assertFalse(row.exceeded)
    }

    @Test
    fun anInvalidStoredAppLimitIsFlaggedAndNotRepaired() {
        val row = appEvaluation(usedMs = 10 * minute, invalidLimitMinutes = -30).toAppRow()!!

        assertTrue(row.invalidLimit)
        assertFalse("a corrupt limit is not read as unlimited", row.hasLimit)
        assertNull(row.limitMinutes)
        assertEquals("usage is still reported honestly", 10 * minute, row.usedMs)
    }

    // ---- only app evaluations become app rows --------------------------------

    @Test
    fun aTotalEvaluationDoesNotBecomeAnAppRow() {
        val total = appEvaluation(scope = LimitScope.TOTAL, limitMinutes = 120, usedMs = 70 * minute)

        assertNull("a total limit must never be shown as an app limit", total.toAppRow())
    }

    @Test
    fun aCategoryEvaluationDoesNotBecomeAnAppRow() {
        val category = appEvaluation(
            scope = LimitScope.CATEGORY,
            limitMinutes = 45,
            usedMs = 40 * minute,
            category = AppCategory.GAMES,
        )

        assertNull("a category limit must never be shown as an app limit", category.toAppRow())
    }

    // ---- multiple apps -------------------------------------------------------

    @Test
    fun appsAreIndependentAndDoNotCrossContaminate() {
        val state = ScreenTimeAppsUiState(
            rows = mapOf(
                youtube to appEvaluation(packageName = youtube, limitMinutes = 30, usedMs = 10 * minute).toAppRow()!!,
                tiktok to appEvaluation(packageName = tiktok, limitMinutes = 60, usedMs = 50 * minute).toAppRow()!!,
            ),
        )

        assertEquals(20 * minute, state.rowFor(youtube)!!.remainingMs)
        assertEquals(10 * minute, state.rowFor(tiktok)!!.remainingMs)
        assertEquals("each package keeps its own label", ScreenTimeInfoLabel.App(tiktok), state.rowFor(tiktok)!!.label)
    }

    @Test
    fun anAppThatWasNeverEvaluatedHasNoRow() {
        val state = ScreenTimeAppsUiState(
            rows = mapOf(youtube to appEvaluation().toAppRow()!!),
        )

        assertNull("nothing is claimed about an app that was not evaluated", state.rowFor(tiktok))
    }

    // ---- states that are not data --------------------------------------------

    @Test
    fun unavailableUsageIsItsOwnStateAndCarriesNoRows() {
        val state = ScreenTimeAppsUiState(usageAvailable = false)

        assertFalse(state.usageAvailable)
        assertNull("no usage may be claimed when it cannot be read", state.rowFor(youtube))
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun loadingIsNotMistakenForNoUsage() {
        val loading = ScreenTimeAppsUiState(loading = true)
        val readyWithNoUsage = ScreenTimeAppsUiState(loading = false, usageAvailable = true)

        assertTrue(loading.loading)
        assertTrue(readyWithNoUsage.usageAvailable)
        assertNull(loading.rowFor(youtube))
        assertNull("an app with no usage and no limit simply has no row", readyWithNoUsage.rowFor(youtube))
    }

    @Test
    fun anErrorIsReportedRatherThanShownAsNoLimit() {
        val state = ScreenTimeAppsUiState(errorMessageRes = 1)

        assertEquals(1, state.errorMessageRes)
        assertNull("a failure never becomes 'no limit'", state.rowFor(youtube))
    }
}
