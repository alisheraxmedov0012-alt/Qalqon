package uz.faceguard.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 Step 1B-6: the checkpoint DAO against the real v8 schema.
 *
 * The point of these tests is the *identity*: a checkpoint may only ever be found, replaced
 * or deleted through its full key (account, child, source, window, package), so one child's
 * baseline can never be read or overwritten as another's.
 */
@RunWith(AndroidJUnit4::class)
class UsageSnapshotCheckpointDaoTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var dao: UsageSnapshotCheckpointDao

    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val source = "USAGE_STATS"
    private val dayStart = 1_790_294_400_000L
    private val dayEnd = 1_790_380_800_000L
    private val nextDayStart = 1_790_380_800_000L
    private val nextDayEnd = 1_790_467_200_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.usageSnapshotCheckpointDao()
    }

    @After
    fun tearDown() = db.close()

    private fun checkpoint(
        accountId: Long = 1L,
        childId: Long = 10L,
        pkg: String = youtube,
        start: Long = dayStart,
        end: Long = dayEnd,
        cumulative: Long = 10_000L,
        observedAt: Long = 1_790_295_000_000L,
    ) = UsageSnapshotCheckpointEntity(
        accountId = accountId,
        childId = childId,
        source = source,
        windowStartMs = start,
        windowEndMs = end,
        packageName = pkg,
        cumulativeForegroundMs = cumulative,
        observedAtMs = observedAt,
    )

    // ---- basic storage ------------------------------------------------------

    @Test
    fun insertAndRead() = runBlocking {
        dao.upsert(checkpoint(cumulative = 12_345L))

        val stored = dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube)

        assertNotNull(stored)
        assertEquals(12_345L, stored!!.cumulativeForegroundMs)
        assertEquals(1_790_295_000_000L, stored.observedAtMs)
        assertEquals(source, stored.source)
    }

    @Test
    fun anAbsentKeyIsNull() = runBlocking {
        assertNull(dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube))
    }

    @Test
    fun upsertSameKeyReplacesInsteadOfDuplicating() = runBlocking {
        dao.upsert(checkpoint(cumulative = 10_000L))
        dao.upsert(checkpoint(cumulative = 15_000L, observedAt = 1_790_299_800_000L))

        val window = dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd)
        assertEquals("one logical row per identity", 1, window.size)
        assertEquals(15_000L, window.single().cumulativeForegroundMs)
        assertEquals("the newer observation wins", 1_790_299_800_000L, window.single().observedAtMs)
    }

    @Test
    fun upsertAllStoresEveryPackageOfAWindow() = runBlocking {
        dao.upsertAll(listOf(checkpoint(pkg = youtube, cumulative = 1_000L), checkpoint(pkg = tiktok, cumulative = 2_000L)))

        val window = dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd)
        assertEquals(listOf(tiktok, youtube), window.map { it.packageName })
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun differentAccountIsolation() = runBlocking {
        dao.upsert(checkpoint(accountId = 1L, cumulative = 10_000L))
        dao.upsert(checkpoint(accountId = 2L, cumulative = 90_000L))

        assertEquals(10_000L, dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
        assertEquals(90_000L, dao.checkpoint(2L, 10L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
        assertEquals(1, dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd).size)
    }

    @Test
    fun differentChildIsolation() = runBlocking {
        dao.upsert(checkpoint(childId = 10L, cumulative = 10_000L))
        dao.upsert(checkpoint(childId = 11L, cumulative = 90_000L))

        assertEquals(10_000L, dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
        assertEquals(90_000L, dao.checkpoint(1L, 11L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
    }

    @Test
    fun differentPackageIsolation() = runBlocking {
        dao.upsert(checkpoint(pkg = youtube, cumulative = 10_000L))
        dao.upsert(checkpoint(pkg = tiktok, cumulative = 90_000L))

        assertEquals(10_000L, dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
        assertEquals(90_000L, dao.checkpoint(1L, 10L, source, dayStart, dayEnd, tiktok)!!.cumulativeForegroundMs)
        assertEquals(2, dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd).size)
    }

    @Test
    fun differentWindowIsolation() = runBlocking {
        dao.upsert(checkpoint(start = dayStart, end = dayEnd, cumulative = 10_000L))
        dao.upsert(checkpoint(start = nextDayStart, end = nextDayEnd, cumulative = 90_000L))

        assertEquals(10_000L, dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
        assertEquals(90_000L, dao.checkpoint(1L, 10L, source, nextDayStart, nextDayEnd, youtube)!!.cumulativeForegroundMs)
        assertEquals("a window query only sees its own window", 1, dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd).size)
    }

    @Test
    fun differentSourceIsolation() = runBlocking {
        dao.upsert(checkpoint(cumulative = 10_000L))
        dao.upsert(checkpoint(cumulative = 90_000L).copy(source = "OTHER_SOURCE"))

        assertEquals(1, dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd).size)
        assertEquals(10_000L, dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube)!!.cumulativeForegroundMs)
    }

    // ---- delete / cleanup ---------------------------------------------------

    @Test
    fun deleteTargetsOneIdentityOnly() = runBlocking {
        dao.upsertAll(listOf(checkpoint(pkg = youtube), checkpoint(pkg = tiktok)))

        dao.delete(1L, 10L, source, dayStart, dayEnd, youtube)

        assertNull(dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube))
        assertNotNull("the other package is untouched", dao.checkpoint(1L, 10L, source, dayStart, dayEnd, tiktok))
    }

    @Test
    fun deleteForChildClearsThatChildOnly() = runBlocking {
        dao.upsertAll(
            listOf(
                checkpoint(childId = 10L),
                checkpoint(childId = 11L),
                checkpoint(accountId = 2L, childId = 10L),
            ),
        )

        dao.deleteForChild(1L, 10L)

        assertNull(dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube))
        assertNotNull(dao.checkpoint(1L, 11L, source, dayStart, dayEnd, youtube))
        assertNotNull("another account's child is untouched", dao.checkpoint(2L, 10L, source, dayStart, dayEnd, youtube))
    }

    @Test
    fun deleteForAccountClearsThatAccountOnly() = runBlocking {
        dao.upsertAll(
            listOf(
                checkpoint(accountId = 1L, childId = 10L),
                checkpoint(accountId = 1L, childId = 11L),
                checkpoint(accountId = 2L, childId = 10L),
            ),
        )

        dao.deleteForAccount(1L)

        assertNull(dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube))
        assertNull(dao.checkpoint(1L, 11L, source, dayStart, dayEnd, youtube))
        assertNotNull(dao.checkpoint(2L, 10L, source, dayStart, dayEnd, youtube))
    }

    // ---- concurrency --------------------------------------------------------

    @Test
    fun concurrentUpsertsOfOneIdentityLeaveExactlyOneRow() = runBlocking {
        val entity = checkpoint()

        coroutineScope {
            (1..CONCURRENT_WRITERS).map { async { dao.upsert(entity.copy(cumulativeForegroundMs = it.toLong())) } }.awaitAll()
        }

        val window = dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd)
        assertEquals("no duplicate logical rows", 1, window.size)
        assertNotNull(dao.checkpoint(1L, 10L, source, dayStart, dayEnd, youtube))
    }

    @Test
    fun concurrentUpsertsOfDifferentIdentitiesAllSurvive() = runBlocking {
        coroutineScope {
            (1..CONCURRENT_WRITERS).map { index ->
                async { dao.upsert(checkpoint(pkg = "com.example.app$index", cumulative = index.toLong())) }
            }.awaitAll()
        }

        assertEquals(
            "different identities never collide",
            CONCURRENT_WRITERS,
            dao.checkpointsForWindow(1L, 10L, source, dayStart, dayEnd).size,
        )
    }

    private companion object {
        const val CONCURRENT_WRITERS = 25
    }
}
