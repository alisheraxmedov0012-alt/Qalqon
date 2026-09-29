package uz.faceguard.app.requests

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ExtraTimeRequestResult
import uz.faceguard.app.core.protection.ExtraTimeRequester
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestCreationResult
import uz.faceguard.app.domain.request.RequestDeduplication
import uz.faceguard.app.domain.request.RequestLimits
import uz.faceguard.app.domain.request.RequestResolutionResult
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.request.RequestType
import uz.faceguard.app.domain.request.RequestValidation
import uz.faceguard.app.domain.request.RequestValidator

/**
 * In-memory [ParentRequestRepository] that reuses the real domain rules
 * (validation + deduplication) so the reachability seam is exercised against the
 * same semantics production enforces, without Room.
 */
private class FakeParentRequestRepository : ParentRequestRepository {
    val stored = mutableListOf<ParentRequest>()
    var createCalls = 0
    var otherCalls = 0
    var failCreate = false

    override fun observePending(accountId: Long): Flow<List<ParentRequest>> {
        otherCalls++
        return flowOf(stored.filter { it.accountId == accountId && it.isPending })
    }

    override fun observeForAccount(accountId: Long): Flow<List<ParentRequest>> {
        otherCalls++
        return flowOf(stored.filter { it.accountId == accountId })
    }

    override fun observePendingCount(accountId: Long): Flow<Int> {
        otherCalls++
        return flowOf(stored.count { it.accountId == accountId && it.isPending })
    }

    override fun observePendingForChild(accountId: Long, childId: Long): Flow<List<ParentRequest>> {
        otherCalls++
        return flowOf(stored.filter { it.accountId == accountId && it.childId == childId && it.isPending })
    }

    override suspend fun byId(accountId: Long, requestId: Long): ParentRequest? {
        otherCalls++
        return stored.firstOrNull { it.accountId == accountId && it.id == requestId }
    }

    override suspend fun create(request: ParentRequest): RequestCreationResult {
        createCalls++
        if (failCreate) throw IllegalStateException("simulated persistence failure")

        val validation = RequestValidator.validate(
            accountId = request.accountId,
            childId = request.childId,
            targetPackageName = request.targetPackageName,
            requestedDurationMinutes = request.requestedDurationMinutes,
            createdAt = request.createdAt,
            expiresAt = request.expiresAt,
        )
        if (validation is RequestValidation.Invalid) {
            return RequestCreationResult.Rejected(validation.reason.name)
        }

        val key = request.deduplicationKey
        val active = stored.firstOrNull {
            it.accountId == request.accountId && it.deduplicationKey == key && it.isPending
        }
        if (active != null && RequestDeduplication.isDuplicateActive(active, key, request.createdAt)) {
            return RequestCreationResult.Duplicate(active)
        }

        val toInsert = request.copy(id = (stored.maxOfOrNull { it.id } ?: 0L) + 1)
        stored += toInsert
        return RequestCreationResult.Created(toInsert)
    }

    override suspend fun approve(
        accountId: Long,
        requestId: Long,
        approvedDurationMinutes: Int?,
        now: Long,
    ): RequestResolutionResult {
        otherCalls++
        return RequestResolutionResult.NotFound("not used in this test")
    }

    override suspend fun reject(accountId: Long, requestId: Long, now: Long): RequestResolutionResult {
        otherCalls++
        return RequestResolutionResult.NotFound("not used in this test")
    }

    override suspend fun cancel(accountId: Long, requestId: Long, now: Long): RequestResolutionResult {
        otherCalls++
        return RequestResolutionResult.NotFound("not used in this test")
    }

    override suspend fun expireStale(accountId: Long, now: Long): Int {
        otherCalls++
        return 0
    }
}

/**
 * Phase 7.3: the child's "request extra time" control must actually reach the
 * existing request machinery — creating a durable PENDING request that the
 * existing parent-side surface can see — without granting anything, without
 * disabling protection, and without a request failure ever escaping.
 */
class ExtraTimeRequestReachabilityTest {

    private val pkg = "com.example.youtube"
    private val fixedNow = 1_700_000_000_000L

    private fun requester(repository: FakeParentRequestRepository, now: Long = fixedNow) =
        ExtraTimeRequester(repository, clock = { now })

    // ---- reachability ------------------------------------------------------

    @Test
    fun theOverlayTapReachesTheRequestRepository() = runBlocking {
        val repo = FakeParentRequestRepository()

        val result = requester(repo).request(accountId = 1L, childId = 5L, packageName = pkg)

        assertTrue("the tap must reach the existing repository", result is ExtraTimeRequestResult.Created)
        assertEquals(1, repo.createCalls)
        assertEquals(1, repo.stored.size)
    }

    @Test
    fun aValidBlockedStateCreatesAPendingRequest() = runBlocking {
        val repo = FakeParentRequestRepository()

        val result = requester(repo).request(1L, 5L, pkg) as ExtraTimeRequestResult.Created
        val request = result.request

        assertEquals(RequestStatus.PENDING, request.status)
        assertTrue(request.isPending)
        assertEquals(1L, request.accountId)
        assertEquals(5L, request.childId)
        assertEquals(pkg, request.targetPackageName)
        assertEquals(RequestLimits.DEFAULT_REQUEST_MINUTES, request.requestedDurationMinutes)
        // The existing domain rule builds the identity of "the same request".
        assertEquals(
            RequestDeduplication.keyFor(1L, 5L, RequestType.EXTRA_TIME, pkg),
            request.deduplicationKey,
        )
        assertEquals("the request uses the injected clock", fixedNow, request.createdAt)
    }

    @Test
    fun theCreatedRequestIsPersistedAndVisibleOnTheExistingParentSurface() = runBlocking {
        val repo = FakeParentRequestRepository()
        val created = (requester(repo).request(1L, 5L, pkg) as ExtraTimeRequestResult.Created).request

        // Parent-side surfaces read through the existing repository queries.
        assertTrue(repo.observePending(1L).first().any { it.id == created.id })
        assertTrue(repo.observeForAccount(1L).first().any { it.id == created.id })
        assertTrue(repo.observePendingForChild(1L, 5L).first().any { it.id == created.id })
        assertEquals(1, repo.observePendingCount(1L).first())
    }

    // ---- duplication (existing semantics) ----------------------------------

    @Test
    fun aRepeatedTapInsideTheWindowIsDeduplicated() = runBlocking {
        val repo = FakeParentRequestRepository()
        val seam = requester(repo)

        val first = seam.request(1L, 5L, pkg)
        val second = seam.request(1L, 5L, pkg)

        assertTrue(first is ExtraTimeRequestResult.Created)
        assertTrue("a repeated tap must not create a second request", second is ExtraTimeRequestResult.Duplicate)
        assertEquals(1, repo.stored.size)
        assertEquals(2, repo.createCalls)
    }

    @Test
    fun aLaterTapOutsideTheWindowIsLegitimate() = runBlocking {
        val repo = FakeParentRequestRepository()
        val seam = requester(repo)

        assertTrue(seam.request(1L, 5L, pkg) is ExtraTimeRequestResult.Created)
        val later = requester(repo, now = fixedNow + RequestLimits.DEDUP_WINDOW_MS + 1).request(1L, 5L, pkg)

        assertTrue("outside the dedup window a new request is allowed", later is ExtraTimeRequestResult.Created)
        assertEquals(2, repo.stored.size)
    }

    @Test
    fun aResolvedRequestNeverBlocksALaterTap() = runBlocking {
        val repo = FakeParentRequestRepository()
        assertTrue(requester(repo).request(1L, 5L, pkg) is ExtraTimeRequestResult.Created)
        repo.stored[0] = repo.stored[0].copy(status = RequestStatus.APPROVED)

        val again = requester(repo).request(1L, 5L, pkg)

        assertTrue(again is ExtraTimeRequestResult.Created)
        assertEquals(2, repo.stored.size)
    }

    @Test
    fun differentChildrenDoNotCollide() = runBlocking {
        val repo = FakeParentRequestRepository()
        val seam = requester(repo)

        assertTrue(seam.request(1L, 5L, pkg) is ExtraTimeRequestResult.Created)
        assertTrue("another child's request is its own", seam.request(1L, 6L, pkg) is ExtraTimeRequestResult.Created)
        assertEquals(2, repo.stored.size)
    }

    // ---- security / semantics ----------------------------------------------

    @Test
    fun aPendingRequestGrantsNothing() = runBlocking {
        val repo = FakeParentRequestRepository()
        val request = (requester(repo).request(1L, 5L, pkg) as ExtraTimeRequestResult.Created).request

        assertNull("no authorization is granted by creating a request", request.approvedDurationMinutes)
        assertNull("no resolution is recorded", request.resolvedAt)
        assertEquals(RequestStatus.PENDING, request.status)
    }

    @Test
    fun creatingARequestHasNoSideEffectOtherThanTheRequest() = runBlocking {
        val repo = FakeParentRequestRepository()
        requester(repo).request(1L, 5L, pkg)

        // The seam touches the request repository only — it holds no reference to
        // protection, so a request can never disable protection or bypass policy.
        assertEquals(1, repo.createCalls)
        assertEquals("only create() may be used", 0, repo.otherCalls)
    }

    // ---- failure isolation -------------------------------------------------

    @Test
    fun aRepositoryFailureIsIsolatedAndReported() = runBlocking {
        val repo = FakeParentRequestRepository().apply { failCreate = true }

        val result = requester(repo).request(1L, 5L, pkg)

        assertTrue("a persistence failure must be reported, never thrown", result is ExtraTimeRequestResult.Failed)
        assertTrue(repo.stored.isEmpty())
    }

    @Test
    fun aRejectedRequestIsReportedAsFailedNotCreated() = runBlocking {
        val repo = FakeParentRequestRepository()

        val result = requester(repo).request(1L, 5L, "not a package")

        assertTrue(result is ExtraTimeRequestResult.Failed)
        assertTrue(repo.stored.isEmpty())
    }

    @Test
    fun anIncompleteRuntimeContextWritesNothing() = runBlocking {
        val repo = FakeParentRequestRepository()
        val seam = requester(repo)

        assertTrue(seam.request(null, 5L, pkg) is ExtraTimeRequestResult.Unavailable)
        assertTrue(seam.request(0L, 5L, pkg) is ExtraTimeRequestResult.Unavailable)
        assertTrue(seam.request(1L, null, pkg) is ExtraTimeRequestResult.Unavailable)
        assertTrue(seam.request(1L, 0L, pkg) is ExtraTimeRequestResult.Unavailable)
        assertTrue(seam.request(1L, 5L, null) is ExtraTimeRequestResult.Unavailable)
        assertTrue(seam.request(1L, 5L, "   ") is ExtraTimeRequestResult.Unavailable)

        assertEquals("no context, no request", 0, repo.createCalls)
        assertTrue(repo.stored.isEmpty())
        assertFalse(repo.stored.any { it.isPending })
    }
}
