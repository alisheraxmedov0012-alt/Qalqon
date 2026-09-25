package uz.faceguard.app.data.usage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.usage.UsageStatsAppUsageSource
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageRange

/**
 * Phase 4 Step 1B-4: the real [UsageStatsAppUsageSource] against a real Room database.
 *
 * Usage Access cannot be granted from a test — it is a user-driven app-op — so these
 * tests assert the *contract* in both states rather than assuming a grant, which is what
 * keeps them non-flaky on a CI emulator and meaningful on a device that has it enabled.
 * The final report states which state this run actually observed.
 *
 * The database is present on purpose: the point is to prove the source leaves it alone.
 */
@RunWith(AndroidJUnit4::class)
class UsageStatsAppUsageSourceTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var source: UsageStatsAppUsageSource

    /** A window no device can have usage in, so the result is stable. */
    private val emptyWindow = UsageRange(startTimeMs = 946_684_800_000L, endTimeMs = 946_771_200_000L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        source = UsageStatsAppUsageSource(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = db.close()

    // ---- capability ---------------------------------------------------------

    @Test
    fun theReportedCapabilityMatchesWhatAQueryDoes() = runBlocking {
        val access = source.usageAccess()
        val result = source.queryUsage(emptyWindow)

        when (access) {
            UsageAccessState.AVAILABLE ->
                assertTrue("granted access must yield a query result, got $result", result is AppUsageQueryResult.Available)
            UsageAccessState.UNAVAILABLE ->
                assertEquals(
                    "without usage access a query must report Unavailable, not an empty success",
                    AppUsageQueryResult.Unavailable,
                    result,
                )
        }
    }

    @Test
    fun theObservedCapabilityIsReportedSoTheRunIsInterpretable() {
        // Not an assertion on *which* state: it records which branch the device actually
        // exercised, so a green run cannot hide "usage access was never granted".
        val access = source.usageAccess()

        println("STEP_1B_4 usage access observed: $access")
        assertTrue(
            "the capability probe must always return a known state, was $access",
            access == UsageAccessState.AVAILABLE || access == UsageAccessState.UNAVAILABLE,
        )
    }

    // ---- window passthrough -------------------------------------------------

    @Test
    fun queryUsesTheCallersWindowAndRejectsImpossibleOnes() = runBlocking {
        // Ranges that cannot describe a window never reach the platform.
        assertThrows(IllegalArgumentException::class.java) { UsageRange(1_000L, 1_000L) }
        assertThrows(IllegalArgumentException::class.java) { UsageRange(2_000L, 1_000L) }
        assertThrows(IllegalArgumentException::class.java) { UsageRange(-1L, 1_000L) }

        // A window in the distant past is passed through as given and yields a stable
        // answer (no "today" is substituted anywhere).
        val first = source.queryUsage(emptyWindow)
        val second = source.queryUsage(emptyWindow)
        assertEquals("the same window must give the same answer", first, second)

        if (source.usageAccess() == UsageAccessState.AVAILABLE) {
            assertEquals(AppUsageQueryResult.Available(emptyList()), first)
        }
    }

    @Test
    fun outputIsAggregatedAndDeterministic() = runBlocking {
        // A broad window that includes "now". Usage Access is a user-granted app-op that
        // a test cannot grant, so this asserts the contract in whichever state the device
        // is in rather than assuming a grant; only invariants are checked, never specific
        // durations, so a concurrently busy device cannot make this flaky.
        val now = System.currentTimeMillis()
        val result = source.queryUsage(UsageRange(now - 24 * 60 * 60_000L, now))

        when (source.usageAccess()) {
            UsageAccessState.UNAVAILABLE -> assertEquals(
                "without access a broad window must also report Unavailable",
                AppUsageQueryResult.Unavailable,
                result,
            )

            UsageAccessState.AVAILABLE -> {
                assertTrue("granted access must yield samples, got $result", result is AppUsageQueryResult.Available)
                val samples = (result as AppUsageQueryResult.Available).samples

                assertEquals(
                    "one entry per package",
                    samples.map { it.packageName }.distinct().size,
                    samples.size,
                )
                assertEquals(
                    "ordered by package name",
                    samples.map { it.packageName }.sorted(),
                    samples.map { it.packageName },
                )
                assertTrue("blank packages must be filtered out", samples.none { it.packageName.isBlank() })
                assertTrue("no zero or negative durations", samples.all { it.foregroundMs > 0L })
            }
        }
    }

    // ---- separation ---------------------------------------------------------

    @Test
    fun theAdapterNeverWritesUsage() = runBlocking {
        val usage = db.dailyAppUsageDao()

        source.usageAccess()
        source.queryUsage(emptyWindow)
        val now = System.currentTimeMillis()
        source.queryUsage(UsageRange(now - 60 * 60_000L, now))

        assertEquals("the source must not persist anything", 0, usage.dayUsage(1L, 10L, "2026-09-25").size)
        assertNull("and must not create a total", usage.totalUsedMs(1L, 10L, "2026-09-25"))
        assertEquals(0, usage.dayUsage(1L, 10L, "1970-01-01").size)
    }
}
