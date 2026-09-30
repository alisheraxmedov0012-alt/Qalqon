package uz.faceguard.app.screentime

import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageRange

/**
 * Phase 4 Step 1B-4 (pure JVM): the contract of [AppUsageSource] and the separations that
 * keep Android out of the domain layer.
 *
 * The Android implementation cannot run here (no `UsageStatsManager` on a JVM), so its
 * behaviour is proven on a device in `UsageStatsAppUsageSourceTest`. What *is* provable
 * here is the shape of the seam: access is explicit, the caller's window is used verbatim,
 * and the source cannot express an account, a child, a category or a write.
 */
class AppUsageSourceContractTest {

    private val youtube = "com.google.android.youtube"

    /** Records what it was asked, so the caller's window can be asserted exactly. */
    private class RecordingSource(
        private val access: UsageAccessState,
        private val samples: List<AppUsageSample> = emptyList(),
    ) : AppUsageSource {
        var lastRange: UsageRange? = null
            private set

        override fun usageAccess(): UsageAccessState = access

        override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult {
            lastRange = range
            return if (access == UsageAccessState.AVAILABLE) {
                AppUsageQueryResult.Available(samples)
            } else {
                AppUsageQueryResult.Unavailable
            }
        }
    }

    private fun range(start: Long = 0L, end: Long = 60_000L) = UsageRange(start, end)

    // ---- capability ---------------------------------------------------------

    @Test
    fun usageAccess_available_returnsAvailable() {
        assertEquals(UsageAccessState.AVAILABLE, RecordingSource(UsageAccessState.AVAILABLE).usageAccess())
    }

    @Test
    fun usageAccessUnavailable_returnsUnavailable() {
        assertEquals(UsageAccessState.UNAVAILABLE, RecordingSource(UsageAccessState.UNAVAILABLE).usageAccess())
    }

    @Test
    fun accessStateCoversExactlyTheTwoKnowableStates() {
        assertEquals(2, UsageAccessState.entries.size)
        assertTrue(UsageAccessState.entries.containsAll(listOf(UsageAccessState.AVAILABLE, UsageAccessState.UNAVAILABLE)))
    }

    @Test
    fun withoutAccessAQueryReportsUnavailableRatherThanAnEmptySuccess() = runBlocking {
        val result = RecordingSource(UsageAccessState.UNAVAILABLE).queryUsage(range())

        // "We may not read usage" and "the device recorded nothing" are different facts
        // and must not collapse into one another.
        assertEquals(AppUsageQueryResult.Unavailable, result)
    }

    @Test
    fun withAccessAnEmptyWindowIsAnEmptySuccessNotUnavailable() = runBlocking {
        val result = RecordingSource(UsageAccessState.AVAILABLE).queryUsage(range())

        assertEquals(AppUsageQueryResult.Available(emptyList()), result)
    }

    // ---- query window -------------------------------------------------------

    @Test
    fun queryUsesExactStartAndEndRange() = runBlocking {
        val source = RecordingSource(UsageAccessState.AVAILABLE)
        val asked = range(start = 1_700_000_000_000L, end = 1_700_000_600_000L)

        source.queryUsage(asked)

        assertEquals(asked, source.lastRange)
        assertEquals(1_700_000_000_000L, source.lastRange!!.startTimeMs)
        assertEquals(1_700_000_600_000L, source.lastRange!!.endTimeMs)
    }

    @Test
    fun queryDoesNotUseHardCodedTodayRange() = runBlocking {
        val source = RecordingSource(UsageAccessState.AVAILABLE)

        // A window far in the past must be passed through untouched; nothing may
        // silently substitute "now" or a day-aligned range.
        val historical = range(start = 946_684_800_000L, end = 946_771_200_000L)
        source.queryUsage(historical)

        assertEquals(historical, source.lastRange)
    }

    @Test
    fun equalStartAndEndIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsageRange(1_000L, 1_000L) }
    }

    @Test
    fun endBeforeStartIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsageRange(2_000L, 1_000L) }
    }

    @Test
    fun negativeStartIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UsageRange(-1L, 1_000L) }
        assertThrows(IllegalArgumentException::class.java) { UsageRange(-1_000L, -500L) }
    }

    // ---- separations --------------------------------------------------------

    @Test
    fun theSampleCarriesAPackageAndADurationAndNothingElse() {
        val fields = AppUsageSample::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        // No childId, no accountId, no category: a usage source cannot assign any of them,
        // so device usage can never be attributed to a child or a policy from here.
        assertEquals(listOf("foregroundMs", "packageName"), fields)
    }

    @Test
    fun theSourceCanOnlyBeAskedForAWindow() {
        val methods = AppUsageSource::class.java.declaredMethods

        val query = methods.single { it.name == "queryUsage" }
        assertEquals("the caller's window is the first argument", UsageRange::class.java, query.parameterTypes.first())
        // A suspend function compiles with a trailing Continuation; that is the only
        // extra parameter allowed, so no account/child/category can slip in.
        val extras = query.parameterTypes.drop(1)
        assertTrue(
            "only the suspend continuation may follow the window, found ${extras.toList()}",
            extras.all { it.name == "kotlin.coroutines.Continuation" },
        )
        assertTrue("a query takes at most the window plus its continuation", query.parameterCount <= 2)

        assertTrue(
            "only the capability probe and the query may exist",
            methods.map { it.name }.toSet() == setOf("usageAccess", "queryUsage"),
        )
    }

    @Test
    fun theSourceSurfaceMentionsNoPersistenceOrAttribution() {
        val surface = buildString {
            append(AppUsageSource::class.java.methods.joinToString { it.toGenericString() })
            append(AppUsageSample::class.java.declaredFields.joinToString { it.name })
        }

        listOf("Room", "Dao", "Entity", "insert", "save", "persist", "childId", "accountId", "AppCategory")
            .forEach { forbidden ->
                assertTrue("the usage source must not expose '$forbidden'", !surface.contains(forbidden))
            }
    }
}
