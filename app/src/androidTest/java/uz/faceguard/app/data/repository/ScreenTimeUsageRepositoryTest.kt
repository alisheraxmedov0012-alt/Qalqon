package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository

/**
 * Phase 4 Step 1B-2: real Room-backed tests for [ScreenTimeUsageRepositoryImpl].
 *
 * These exercise the production path (repository validation + DAO + entity/domain
 * mapping) against the real v7 `FaceGuardDatabase`, not a mock. Isolation is asserted
 * per account, child, day and package, because usage filed under the wrong key is a
 * correctness bug the parent would silently see.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTimeUsageRepositoryTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ScreenTimeUsageRepository

    private val day = "2026-09-24"
    private val nextDay = "2026-09-25"
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"
    private val khanAcademy = "org.khanacademy.android"
    private val clashOfClans = "com.supercell.clashofclans"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ScreenTimeUsageRepositoryImpl(db.dailyAppUsageDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(
        accountId: Long = 1L,
        childId: Long = 10L,
        dateKey: String = day,
        pkg: String = youtube,
        category: AppCategory = AppCategory.VIDEO,
        deltaMs: Long,
    ) = repository.addUsage(accountId, childId, dateKey, pkg, category, deltaMs)

    // ---- writes -------------------------------------------------------------

    @Test
    fun addUsage_createsNewUsageRow() = runBlocking {
        add(deltaMs = 1_000L)

        assertEquals(1_000L, repository.getAppUsageMs(1L, 10L, day, youtube))
        val rows = repository.getDayUsage(1L, 10L, day)
        assertEquals(1, rows.size)
        assertEquals(AppUsage(youtube, AppCategory.VIDEO, 1_000L), rows.single())
    }

    @Test
    fun addUsage_accumulatesUsage() = runBlocking {
        add(deltaMs = 1_000L)
        add(deltaMs = 500L)
        add(deltaMs = 700L)

        assertEquals("1000 + 500 + 700", 2_200L, repository.getAppUsageMs(1L, 10L, day, youtube))
        assertEquals("still one bucket for the package", 1, repository.getDayUsage(1L, 10L, day).size)
        assertEquals(2_200L, repository.getTotalUsageMs(1L, 10L, day))
    }

    @Test
    fun addUsage_zeroDelta_doesNotCorruptUsage() = runBlocking {
        add(deltaMs = 1_000L)

        add(deltaMs = 0L)

        assertEquals("a zero delta is a no-op, not a reset", 1_000L, repository.getAppUsageMs(1L, 10L, day, youtube))
        add(pkg = tiktok, category = AppCategory.SOCIAL, deltaMs = 0L)
        assertEquals("no phantom row for a zero report", 0L, repository.getAppUsageMs(1L, 10L, day, tiktok))
        assertEquals(listOf(youtube), repository.getDayUsage(1L, 10L, day).map { it.packageName })
    }

    @Test
    fun addUsage_negativeDelta_isRejected() = runBlocking {
        add(deltaMs = 1_000L)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { add(deltaMs = -500L) }
        }

        assertEquals(
            "the rejected delta must not reduce the stored total",
            1_000L,
            repository.getAppUsageMs(1L, 10L, day, youtube),
        )
    }

    @Test
    fun packageNameIsTrimmedSoAWriteAndItsReadAgree() = runBlocking {
        add(pkg = "  $youtube  ", deltaMs = 1_000L)

        assertEquals(1_000L, repository.getAppUsageMs(1L, 10L, day, youtube))
        assertEquals(youtube, repository.getDayUsage(1L, 10L, day).single().packageName)
    }

    // ---- reads on an empty day ---------------------------------------------

    @Test
    fun getAppUsage_empty_returnsZero() = runBlocking {
        assertEquals(0L, repository.getAppUsageMs(1L, 10L, day, youtube))
    }

    @Test
    fun getCategoryUsage_empty_returnsZero() = runBlocking {
        assertEquals(0L, repository.getCategoryUsageMs(1L, 10L, day, AppCategory.EDUCATION))
    }

    @Test
    fun getTotalUsage_empty_returnsZero() = runBlocking {
        assertEquals(0L, repository.getTotalUsageMs(1L, 10L, day))
        assertTrue(repository.getDayUsage(1L, 10L, day).isEmpty())
    }

    @Test
    fun getDayUsage_returnsAllAppsForChildAndDate() = runBlocking {
        add(pkg = youtube, category = AppCategory.VIDEO, deltaMs = 1_000L)
        add(pkg = tiktok, category = AppCategory.SOCIAL, deltaMs = 2_000L)
        add(pkg = duolingo, category = AppCategory.EDUCATION, deltaMs = 500L)

        val rows = repository.getDayUsage(1L, 10L, day)

        assertEquals(3, rows.size)
        assertEquals(
            mapOf(
                youtube to AppCategory.VIDEO,
                tiktok to AppCategory.SOCIAL,
                duolingo to AppCategory.EDUCATION,
            ),
            rows.associate { it.packageName to it.category },
        )
        assertEquals(3_500L, rows.sumOf { it.usedMs })
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun accountIsolation() = runBlocking {
        add(accountId = 1L, deltaMs = 1_000L)
        add(accountId = 2L, deltaMs = 2_000L)

        assertEquals(1_000L, repository.getTotalUsageMs(1L, 10L, day))
        assertEquals(2_000L, repository.getTotalUsageMs(2L, 10L, day))
        assertEquals(1_000L, repository.getAppUsageMs(1L, 10L, day, youtube))
        assertEquals(1, repository.getDayUsage(1L, 10L, day).size)
    }

    @Test
    fun childIsolation() = runBlocking {
        add(childId = 10L, deltaMs = 1_000L)
        add(childId = 11L, deltaMs = 2_000L)

        assertEquals(1_000L, repository.getTotalUsageMs(1L, 10L, day))
        assertEquals(2_000L, repository.getTotalUsageMs(1L, 11L, day))
        assertEquals(2_000L, repository.getAppUsageMs(1L, 11L, day, youtube))
    }

    @Test
    fun dateIsolation() = runBlocking {
        add(dateKey = "2026-01-01", deltaMs = 1_000L)
        add(dateKey = "2026-01-02", deltaMs = 2_000L)

        assertEquals(1_000L, repository.getTotalUsageMs(1L, 10L, "2026-01-01"))
        assertEquals(2_000L, repository.getTotalUsageMs(1L, 10L, "2026-01-02"))
        assertEquals(1, repository.getDayUsage(1L, 10L, "2026-01-01").size)
    }

    @Test
    fun packageIsolation() = runBlocking {
        add(pkg = youtube, category = AppCategory.VIDEO, deltaMs = 1_000L)
        add(pkg = tiktok, category = AppCategory.SOCIAL, deltaMs = 2_000L)

        assertEquals(1_000L, repository.getAppUsageMs(1L, 10L, day, youtube))
        assertEquals(2_000L, repository.getAppUsageMs(1L, 10L, day, tiktok))
        assertEquals(3_000L, repository.getTotalUsageMs(1L, 10L, day))
    }

    // ---- aggregation --------------------------------------------------------

    @Test
    fun categoryAggregation() = runBlocking {
        add(pkg = duolingo, category = AppCategory.EDUCATION, deltaMs = 1_000L)
        add(pkg = khanAcademy, category = AppCategory.EDUCATION, deltaMs = 500L)
        add(pkg = clashOfClans, category = AppCategory.GAMES, deltaMs = 2_000L)

        assertEquals("1000 + 500", 1_500L, repository.getCategoryUsageMs(1L, 10L, day, AppCategory.EDUCATION))
        assertEquals(2_000L, repository.getCategoryUsageMs(1L, 10L, day, AppCategory.GAMES))
        assertEquals("a category with no usage is 0, not an error", 0L, repository.getCategoryUsageMs(1L, 10L, day, AppCategory.VIDEO))
    }

    @Test
    fun totalAggregation() = runBlocking {
        add(pkg = youtube, category = AppCategory.VIDEO, deltaMs = 1_000L)
        add(pkg = tiktok, category = AppCategory.SOCIAL, deltaMs = 2_000L)
        add(pkg = duolingo, category = AppCategory.EDUCATION, deltaMs = 3_000L)

        assertEquals("1000 + 2000 + 3000", 6_000L, repository.getTotalUsageMs(1L, 10L, day))
    }

    // ---- observation --------------------------------------------------------

    @Test
    fun observeDayUsage_emitsUpdatedValues() = runBlocking {
        add(deltaMs = 1_000L)

        val emissions = CopyOnWriteArrayList<List<AppUsage>>()
        val collector = launch { repository.observeDayUsage(1L, 10L, day).collect { emissions += it } }
        try {
            awaitUntil { emissions.isNotEmpty() }
            assertEquals(1_000L, emissions.last().single().usedMs)
            assertEquals(AppCategory.VIDEO, emissions.last().single().category)

            add(deltaMs = 500L)

            awaitUntil { emissions.lastOrNull()?.singleOrNull()?.usedMs == 1_500L }
            assertEquals(1_500L, emissions.last().single().usedMs)
        } finally {
            collector.cancelAndJoin()
        }
    }

    // ---- concurrency --------------------------------------------------------

    @Test
    fun concurrentIncrement_doesNotLoseUpdates() = runBlocking {
        coroutineScope {
            repeat(INCREMENTS) {
                launch { add(deltaMs = 1_000L) }
            }
        }

        assertEquals(
            "every concurrent report must be in the total",
            INCREMENTS * 1_000L,
            repository.getAppUsageMs(1L, 10L, day, youtube),
        )
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun invalidInput_isRejectedBeforeItReachesTheDatabase() = runBlocking {
        val tooLong = ScreenTimeUsageRepository.MAX_DELTA_MS + 1L

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { add(accountId = 0L, deltaMs = 1L) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { add(childId = -1L, deltaMs = 1L) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { add(dateKey = "2026-1-1", deltaMs = 1L) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { add(pkg = "   ", deltaMs = 1L) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { add(deltaMs = tooLong) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.getAppUsageMs(-1L, 10L, day, youtube) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.observeDayUsage(1L, 10L, "not-a-date") }
        }

        assertEquals("nothing may have been written", emptyList<AppUsage>(), repository.getDayUsage(1L, 10L, day))
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10L)
        }
    }

    private companion object {
        /** Enough parallel writers to expose a lost update, small enough to stay fast. */
        const val INCREMENTS = 100
    }
}
