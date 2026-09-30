package uz.faceguard.app.requests

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.FaceGuardApp
import uz.faceguard.app.core.protection.ExtraTimeRequestResult
import uz.faceguard.app.core.protection.ExtraTimeRequester
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentRequestRepositoryImpl
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestLimits
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.security.PassthroughTemplateCipher

/**
 * Phase 7.3: the extra-time request path end to end over the *real* Room-backed
 * repository (no mocks) — a child tap creates a durable PENDING request that the
 * existing parent-side queries return — plus a check that issuing a request has no
 * protection side effect at the real runtime boundary.
 *
 * Requires a device/emulator to execute; it is compiled by
 * `assembleDebugAndroidTest` but not run where none is available.
 */
@RunWith(AndroidJUnit4::class)
class ExtraTimeRequestReachabilityInstrumentedTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ParentRequestRepository
    private var childId = 0L

    private val clock = 1_700_000_000_000L
    private val pkg = "com.example.youtube"

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app get() = appContext as FaceGuardApp
    private val runtime: ProtectionRuntime get() = app.protectionRuntime

    private val requester: ExtraTimeRequester
        get() = ExtraTimeRequester(repository, clock = { clock })

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ParentRequestRepositoryImpl(
            dao = db.parentRequestDao(),
            childProfileRepository = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher),
        )
        childId = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher)
            .addChild(1L, "Vali", RestrictionLevel.HIGH)
        assertTrue("the test child must exist", childId > 0L)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aChildTapCreatesADurablePendingRequestVisibleOnTheParentSurface() = runBlocking {
        val result = requester.request(accountId = 1L, childId = childId, packageName = pkg)

        assertTrue("expected a created request, got $result", result is ExtraTimeRequestResult.Created)
        val created = (result as ExtraTimeRequestResult.Created).request
        assertTrue(created.id > 0L)

        val loaded = repository.byId(1L, created.id)
        assertNotNull(loaded)
        assertEquals(RequestStatus.PENDING, loaded!!.status)
        assertEquals(RequestLimits.DEFAULT_REQUEST_MINUTES, loaded.requestedDurationMinutes)
        assertNull("a request grants nothing", loaded.approvedDurationMinutes)

        // The existing parent-side queries see it.
        assertTrue(repository.observePending(1L).first().any { it.id == created.id })
        assertTrue(repository.observePendingForChild(1L, childId).first().any { it.id == created.id })
        assertEquals(1, repository.observePendingCount(1L).first())
    }

    @Test
    fun aRepeatedTapIsDeduplicatedByTheRealRepository() = runBlocking {
        val first = requester.request(1L, childId, pkg)
        val second = requester.request(1L, childId, pkg)

        assertTrue(first is ExtraTimeRequestResult.Created)
        assertTrue("a repeated tap must not create a second request", second is ExtraTimeRequestResult.Duplicate)
        assertEquals(1, repository.observePending(1L).first().size)
    }

    @Test
    fun aChildFromAnotherAccountCannotBeRequestedFor() = runBlocking {
        val result = requester.request(accountId = 2L, childId = childId, packageName = pkg)

        assertTrue("a foreign child must not be requestable", result is ExtraTimeRequestResult.Failed)
        assertTrue(repository.observePending(2L).first().isEmpty())
    }

    @Test
    fun issuingARequestDoesNotChangeProtectionState() = runBlocking {
        val before = runtime.state.value

        runtime.requestExtraTime()

        val after = runtime.state.value
        assertEquals("a request never toggles protection", before.enabled, after.enabled)
        assertEquals("a request never activates/deactivates the session", before.active, after.active)
        assertEquals("a request never changes the protection state", before.protectionState, after.protectionState)
    }
}
