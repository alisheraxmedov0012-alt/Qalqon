package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.ActivityEventEntity
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.model.ActivityEventType

/**
 * Real Room-backed tests for [ActivityLogRepositoryImpl]: the production
 * insert/query/clear path, including account isolation (Group 5).
 */
@RunWith(AndroidJUnit4::class)
class ActivityLogRepositoryTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ActivityLogRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ActivityLogRepositoryImpl(db.activityEventDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun log_thenQuery_roundTripsTypeDetailAndAccount() = runBlocking {
        repository.log(7L, ActivityEventType.CHILD_BLOCKED, "Vali")

        val events = repository.recent(7L).first()
        assertEquals(1, events.size)
        assertEquals(ActivityEventType.CHILD_BLOCKED, events.first().type)
        assertEquals("Vali", events.first().detail)
        assertEquals(7L, events.first().accountId)
    }

    @Test
    fun query_returnsNewestFirst() = runBlocking {
        val dao = db.activityEventDao()
        dao.insert(ActivityEventEntity(accountId = 1L, type = "NO_FACE", at = 1000L))
        dao.insert(ActivityEventEntity(accountId = 1L, type = "CHILD_BLOCKED", at = 2000L))

        val events = repository.recent(1L).first()
        assertEquals(listOf(ActivityEventType.CHILD_BLOCKED, ActivityEventType.NO_FACE), events.map { it.type })
    }

    @Test
    fun accountIsolation_eventsNeverLeakAcrossAccounts() = runBlocking {
        repository.log(1L, ActivityEventType.CHILD_RECOGNIZED, "A")
        repository.log(2L, ActivityEventType.CHILD_BLOCKED, "B")

        val accountOne = repository.recent(1L).first()
        val accountTwo = repository.recent(2L).first()

        assertEquals(1, accountOne.size)
        assertEquals(ActivityEventType.CHILD_RECOGNIZED, accountOne.first().type)
        assertEquals(1, accountTwo.size)
        assertEquals(ActivityEventType.CHILD_BLOCKED, accountTwo.first().type)
        assertTrue(accountOne.none { it.accountId == 2L })
        assertTrue(accountTwo.none { it.accountId == 1L })
    }

    @Test
    fun clear_removesOnlyTheRequestedAccount() = runBlocking {
        repository.log(1L, ActivityEventType.CHILD_BLOCKED, null)
        repository.log(2L, ActivityEventType.CHILD_BLOCKED, null)

        repository.clear(1L)

        assertTrue(repository.recent(1L).first().isEmpty())
        assertEquals(1, repository.recent(2L).first().size)
    }

    @Test
    fun noFace_isPersistedDistinctlyFromUnknownUser() = runBlocking {
        repository.log(1L, ActivityEventType.NO_FACE, null)
        repository.log(1L, ActivityEventType.UNKNOWN_USER, null)

        val types = repository.recent(1L).first().map { it.type }.toSet()
        assertEquals(setOf(ActivityEventType.NO_FACE, ActivityEventType.UNKNOWN_USER), types)
    }

    @Test
    fun protectionReleased_isPersisted() = runBlocking {
        repository.log(1L, ActivityEventType.PROTECTION_RELEASED, null)

        assertEquals(
            ActivityEventType.PROTECTION_RELEASED,
            repository.recent(1L).first().single().type,
        )
    }

    @Test
    fun corruptedStoredType_fallsBackToUnknownUserInsteadOfCrashing() = runBlocking {
        db.activityEventDao().insert(ActivityEventEntity(accountId = 1L, type = "NOT_A_REAL_TYPE", at = 1L))

        assertEquals(
            ActivityEventType.UNKNOWN_USER,
            repository.recent(1L).first().single().type,
        )
    }
}
