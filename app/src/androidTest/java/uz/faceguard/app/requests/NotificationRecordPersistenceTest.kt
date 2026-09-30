package uz.faceguard.app.requests

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.repository.NotificationRepositoryImpl
import uz.faceguard.app.domain.notification.NotificationDecision
import uz.faceguard.app.domain.notification.NotificationDestination
import uz.faceguard.app.domain.notification.NotificationChannelKind
import uz.faceguard.app.domain.notification.NotificationRepository
import uz.faceguard.app.domain.notification.NotificationType

/**
 * Phase 11: durable notification deduplication in real Room. The dedup key is
 * the primary key, so the first claim wins and repeated processing (duplicate
 * emission, retry, process restart) can never notify twice.
 */
@RunWith(AndroidJUnit4::class)
class NotificationRecordPersistenceTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: NotificationRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = NotificationRepositoryImpl(db.notificationRecordDao())
    }

    @After
    fun tearDown() = db.close()

    private fun decision(key: String, at: Long = 1_000L, requestId: Long? = null) = NotificationDecision(
        type = if (requestId != null) NotificationType.PARENT_REQUEST_CREATED else NotificationType.PROTECTION_BLOCKED,
        channel = if (requestId != null) NotificationChannelKind.PARENT_REQUESTS else NotificationChannelKind.PROTECTION_ALERTS,
        destination = if (requestId != null) NotificationDestination.REQUESTS else NotificationDestination.DASHBOARD,
        notificationId = 1,
        deduplicationKey = key,
        oneShot = true,
        relatedRequestId = requestId,
        accountId = 1L,
        at = at,
    )

    @Test
    fun theFirstClaimWinsAndEveryRepeatedClaimIsRefused() = runBlocking {
        assertTrue(repository.claim(decision("blocked:1:5:com.example.youtube:1")))
        assertFalse(repository.claim(decision("blocked:1:5:com.example.youtube:1")))
        assertFalse(repository.claim(decision("blocked:1:5:com.example.youtube:1", at = 9_999L)))

        assertNotNull(db.notificationRecordDao().byKey("blocked:1:5:com.example.youtube:1"))
    }

    @Test
    fun separateEventsKeepSeparateRecords() = runBlocking {
        assertTrue(repository.claim(decision("blocked:1:5:a:1")))
        assertTrue(repository.claim(decision("blocked:1:5:b:1")))
        assertTrue(repository.claim(decision("request:7", requestId = 7L)))
        assertEquals(3, listOf("blocked:1:5:a:1", "blocked:1:5:b:1", "request:7").count {
            db.notificationRecordDao().byKey(it) != null
        })
    }

    @Test
    fun deliveryStatusIsStoredHonestlyAndSeparatelyFromTheRequest() = runBlocking {
        val key = "request:42"
        repository.claim(decision(key, requestId = 42L))

        repository.markDelivered(key, delivered = false, at = 2_000L)
        val record = db.notificationRecordDao().byKey(key)!!
        assertFalse(record.delivered)
        assertEquals(2_000L, record.deliveryAt)
        // The record proves we claimed the notification even though delivery failed.
        assertEquals(1L, record.accountId)

        repository.markDelivered(key, delivered = true, at = 3_000L)
        assertTrue(db.notificationRecordDao().byKey(key)!!.delivered)
    }

    @Test
    fun theDedupRecordSurvivesARestartOfTheRepository() = runBlocking {
        val key = "blocked:1:5:a:1"
        assertTrue(repository.claim(decision(key)))

        // A new repository instance over the same database (process restart);
        // the key is already claimed.
        val afterRestart = NotificationRepositoryImpl(db.notificationRecordDao())
        assertFalse(afterRestart.claim(decision(key, at = 5_000L)))
    }
}
