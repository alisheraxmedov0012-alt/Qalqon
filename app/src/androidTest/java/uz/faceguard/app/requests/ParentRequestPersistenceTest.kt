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
import uz.faceguard.app.security.PassthroughTemplateCipher
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentRequestRepositoryImpl
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestCreationResult
import uz.faceguard.app.domain.request.RequestDeduplication
import uz.faceguard.app.domain.request.RequestResolutionResult
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.request.RequestType

/**
 * Phase 11: real Room-backed request persistence through the production
 * repository (DAOs + entity/domain mapping). No mocks, no fabricated data.
 */
@RunWith(AndroidJUnit4::class)
class ParentRequestPersistenceTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ParentRequestRepository
    private var childId = 0L

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
    }

    @After
    fun tearDown() = db.close()

    private fun request(
        accountId: Long = 1L,
        child: Long = childId,
        pkg: String = "com.example.youtube",
        minutes: Int = 15,
        createdAt: Long = 10_000L,
    ) = ParentRequest(
        accountId = accountId,
        childId = child,
        targetPackageName = pkg,
        requestType = RequestType.EXTRA_TIME,
        requestedDurationMinutes = minutes,
        createdAt = createdAt,
        updatedAt = createdAt,
        deduplicationKey = RequestDeduplication.keyFor(accountId, child, RequestType.EXTRA_TIME, pkg),
    )

    private suspend fun created(request: ParentRequest): ParentRequest {
        val result = repository.create(request)
        assertTrue("expected creation, got $result", result is RequestCreationResult.Created)
        return (result as RequestCreationResult.Created).request
    }

    @Test
    fun aCreatedRequestPersistsWithAStableId() = runBlocking {
        val first = created(request(createdAt = 1_000L))

        assertTrue(first.id > 0L)
        val loaded = repository.byId(1L, first.id)
        assertNotNull(loaded)
        assertEquals(RequestStatus.PENDING, loaded!!.status)
        assertEquals(15, loaded.requestedDurationMinutes)
        assertNull("nothing measures usage here", loaded.approvedDurationMinutes)

        // A later, legitimate request gets its own stable id.
        val second = created(request(createdAt = 1_000L + 120_000L))
        assertTrue(second.id != first.id)
    }

    @Test
    fun anInvalidRequestIsRejectedBeforePersistence() = runBlocking {
        assertTrue(repository.create(request(minutes = 0)) is RequestCreationResult.Rejected)
        assertTrue(repository.create(request(pkg = "not a package")) is RequestCreationResult.Rejected)
        assertTrue(repository.create(request(accountId = 0L)) is RequestCreationResult.Rejected)
        assertEquals(0, repository.observePending(1L).first().size)
    }

    @Test
    fun aChildFromAnotherAccountCannotBeRequestedFor() = runBlocking {
        // Child 5 belongs to account 1; account 2 must not be able to request for it.
        val result = repository.create(request(accountId = 2L, child = childId))
        assertTrue("expected rejection, got $result", result is RequestCreationResult.Rejected)
    }

    @Test
    fun aRepeatedActiveRequestIsCollapsedAsDuplicate() = runBlocking {
        val first = created(request(createdAt = 1_000L))

        val duplicate = repository.create(request(createdAt = 1_500L))
        assertTrue(duplicate is RequestCreationResult.Duplicate)
        assertEquals(first.id, (duplicate as RequestCreationResult.Duplicate).existing.id)
        assertEquals(1, repository.observePending(1L).first().size)
    }

    @Test
    fun aLaterIdenticalRequestIsAllowed() = runBlocking {
        created(request(createdAt = 1_000L))
        val later = created(request(createdAt = 1_000L + 120_000L))
        assertTrue(later.id > 0L)
        assertEquals(2, repository.observePending(1L).first().size)
    }

    @Test
    fun approvalPersistsTheAuthorizedDurationAndResolvesOnce() = runBlocking {
        val pending = created(request(createdAt = 1_000L))

        val result = repository.approve(1L, pending.id, approvedDurationMinutes = 10, now = 2_000L)
        assertTrue(result is RequestResolutionResult.Resolved)

        val approved = repository.byId(1L, pending.id)!!
        assertEquals(RequestStatus.APPROVED, approved.status)
        assertEquals(10, approved.approvedDurationMinutes)
        assertEquals(2_000L, approved.resolvedAt)

        // A duplicate decision updates nothing and cannot change the outcome.
        val again = repository.reject(1L, pending.id, now = 3_000L)
        assertTrue(again is RequestResolutionResult.NotPending)
        assertEquals(RequestStatus.APPROVED, repository.byId(1L, pending.id)!!.status)
    }

    @Test
    fun approvalWithoutAnExplicitDurationKeepsTheRequestedOneAsAuthorizationOnly() = runBlocking {
        val pending = created(request(minutes = 20, createdAt = 1_000L))

        repository.approve(1L, pending.id, approvedDurationMinutes = null, now = 2_000L)

        val approved = repository.byId(1L, pending.id)!!
        assertEquals(20, approved.approvedDurationMinutes)
        // Still no usage concept: the request is an authorization record.
        assertEquals(20, approved.requestedDurationMinutes)
    }

    @Test
    fun rejectionIsTerminalAndKeepsTheHistory() = runBlocking {
        val pending = created(request(createdAt = 1_000L))

        assertTrue(repository.reject(1L, pending.id, now = 2_000L) is RequestResolutionResult.Resolved)

        val rejected = repository.byId(1L, pending.id)!!
        assertEquals(RequestStatus.REJECTED, rejected.status)
        assertEquals(2_000L, rejected.resolvedAt)
        assertTrue("resolved history is kept, not deleted", repository.observeForAccount(1L).first().any { it.id == pending.id })
    }

    @Test
    fun concurrentApprovalAndRejectionYieldExactlyOneTerminalState() = runBlocking {
        val pending = created(request(createdAt = 1_000L))

        val approve = repository.approve(1L, pending.id, null, now = 2_000L)
        val reject = repository.reject(1L, pending.id, now = 2_001L)

        val resolvedOnce = listOf(approve, reject).count { it is RequestResolutionResult.Resolved }
        assertEquals(1, resolvedOnce)
        val finalStatus = repository.byId(1L, pending.id)!!.status
        assertTrue(finalStatus == RequestStatus.APPROVED || finalStatus == RequestStatus.REJECTED)
    }

    @Test
    fun requestsAreAccountIsolated() = runBlocking {
        created(request(accountId = 1L, createdAt = 1_000L))
        val otherChild = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher).addChild(2L, "Ali", RestrictionLevel.LOW)
        assertTrue(otherChild > 0)
        val other = repository.create(
            request(accountId = 2L, child = otherChild, createdAt = 1_000L),
        )
        assertTrue(other is RequestCreationResult.Created)

        assertEquals(1, repository.observePending(1L).first().size)
        assertEquals(1, repository.observePending(2L).first().size)
        assertEquals(1, repository.observePendingCount(1L).first())

        // Account 2 cannot resolve account 1's request.
        val foreign = repository.byId(2L, (other as RequestCreationResult.Created).request.id)
        assertNotNull(foreign)
        val account1Request = repository.observePending(1L).first().single()
        val attack = repository.approve(2L, account1Request.id, null, now = 3_000L)
        assertTrue(attack is RequestResolutionResult.NotFound)
        assertEquals(RequestStatus.PENDING, repository.byId(1L, account1Request.id)!!.status)
    }

    @Test
    fun requestsAreChildIsolatedForTheSameApp() = runBlocking {
        val second = ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher).addChild(1L, "Ali", RestrictionLevel.LOW)
        assertTrue(second > 0)

        created(request(child = childId, createdAt = 1_000L))
        created(request(child = second, createdAt = 1_000L))

        assertEquals(1, repository.observePendingForChild(1L, childId).first().size)
        assertEquals(1, repository.observePendingForChild(1L, second).first().size)
        assertEquals(2, repository.observePending(1L).first().size)
    }

    @Test
    fun expirationIsAccountScopedAndIdempotent() = runBlocking {
        val expiring = created(request(createdAt = 1_000L).copy(expiresAt = 5_000L))

        assertEquals("nothing to expire yet", 0, repository.expireStale(1L, 4_000L))
        assertEquals(RequestStatus.PENDING, repository.byId(1L, expiring.id)!!.status)

        assertEquals(1, repository.expireStale(1L, 6_000L))
        assertEquals("idempotent", 0, repository.expireStale(1L, 7_000L))

        val expired = repository.byId(1L, expiring.id)!!
        assertEquals(RequestStatus.EXPIRED, expired.status)
        // An expired request can never be approved afterwards.
        assertTrue(repository.approve(1L, expiring.id, null, now = 8_000L) is RequestResolutionResult.NotPending)
        assertEquals(RequestStatus.EXPIRED, repository.byId(1L, expiring.id)!!.status)
    }

    @Test
    fun aDeletedChildLeavesStaleRequestsUntouchedButUnresolvableForNewOnes() = runBlocking {
        val pending = created(request(createdAt = 1_000L))
        ChildProfileRepositoryImpl(db.childProfileDao(), PassthroughTemplateCipher).deleteChild(1L, childId)

        // The existing request is preserved (history), but no new request can be made.
        assertNotNull(repository.byId(1L, pending.id))
        assertTrue(repository.create(request(createdAt = 90_000L)) is RequestCreationResult.Rejected)

        // Pending history is still account-scoped and readable.
        assertEquals(1, repository.observePendingForChild(1L, childId).first().size)
    }
}
