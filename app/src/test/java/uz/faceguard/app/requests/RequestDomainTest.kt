package uz.faceguard.app.requests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.RequestDecision
import uz.faceguard.app.domain.request.RequestDeduplication
import uz.faceguard.app.domain.request.RequestInvalidReason
import uz.faceguard.app.domain.request.RequestLimits
import uz.faceguard.app.domain.request.RequestStateMachine
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.request.RequestType
import uz.faceguard.app.domain.request.RequestValidation
import uz.faceguard.app.domain.request.RequestValidator

/**
 * Phase 11: the request domain rules (state machine, validation, deduplication).
 * Pure JVM — no Android, no fabricated data.
 */
class RequestDomainTest {

    // ---- state machine ------------------------------------------------------

    @Test
    fun pendingCanBeApproved() {
        assertEquals(RequestStatus.APPROVED, RequestStateMachine.resolve(RequestStatus.PENDING, RequestDecision.APPROVE))
    }

    @Test
    fun pendingCanBeRejected() {
        assertEquals(RequestStatus.REJECTED, RequestStateMachine.resolve(RequestStatus.PENDING, RequestDecision.REJECT))
    }

    @Test
    fun pendingCanExpireAndBeCancelled() {
        assertEquals(RequestStatus.EXPIRED, RequestStateMachine.expire(RequestStatus.PENDING))
        assertEquals(RequestStatus.CANCELLED, RequestStateMachine.cancel(RequestStatus.PENDING))
    }

    @Test
    fun resolvedRequestsCannotBeResolvedAgain() {
        val resolved = listOf(
            RequestStatus.APPROVED,
            RequestStatus.REJECTED,
            RequestStatus.EXPIRED,
            RequestStatus.CANCELLED,
        )
        resolved.forEach { status ->
            assertFalse("$status must not resolve again", RequestStateMachine.canResolve(status))
            assertEquals(null, RequestStateMachine.resolve(status, RequestDecision.APPROVE))
            assertEquals(null, RequestStateMachine.resolve(status, RequestDecision.REJECT))
            assertEquals(null, RequestStateMachine.expire(status))
            assertEquals(null, RequestStateMachine.cancel(status))
        }
    }

    @Test
    fun thereIsNoReturnToPendingOrBetweenTerminalStates() {
        val terminal = listOf(
            RequestStatus.APPROVED,
            RequestStatus.REJECTED,
            RequestStatus.EXPIRED,
            RequestStatus.CANCELLED,
        )
        terminal.forEach { current ->
            assertFalse(RequestStateMachine.isLegalTransition(current, RequestStatus.PENDING))
            terminal.forEach { other ->
                assertFalse(
                    "illegal $current -> $other",
                    RequestStateMachine.isLegalTransition(current, other),
                )
            }
            assertTrue(RequestStateMachine.isLegalTransition(current, current).not())
        }
    }

    @Test
    fun onlyPendingTransitionsAreLegal() {
        listOf(RequestStatus.APPROVED, RequestStatus.REJECTED, RequestStatus.EXPIRED, RequestStatus.CANCELLED)
            .forEach { next ->
                assertTrue(RequestStateMachine.isLegalTransition(RequestStatus.PENDING, next))
            }
    }

    @Test
    fun twoDecisionsFromOnePendingStateYieldExactlyOneOutcome() {
        // Concurrency at the domain level: the first transition is legal, and the
        // winner is terminal so the second attempt is refused.
        val first = RequestStateMachine.resolve(RequestStatus.PENDING, RequestDecision.APPROVE)
        assertEquals(RequestStatus.APPROVED, first)
        assertEquals(null, RequestStateMachine.resolve(first!!, RequestDecision.REJECT))
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun aWellFormedRequestValidates() {
        assertEquals(
            RequestValidation.Valid,
            RequestValidator.validate(1L, 5L, "com.example.youtube", 15, createdAt = 1_000L),
        )
    }

    @Test
    fun invalidAccountOrChildIsRejected() {
        assertEquals(
            RequestValidation.Invalid(RequestInvalidReason.INVALID_ACCOUNT),
            RequestValidator.validate(0L, 5L, "com.example.youtube", 15, 1_000L),
        )
        assertEquals(
            RequestValidation.Invalid(RequestInvalidReason.INVALID_CHILD),
            RequestValidator.validate(1L, 0L, "com.example.youtube", 15, 1_000L),
        )
    }

    @Test
    fun invalidTargetPackageIsRejected() {
        listOf(null, "", "   ", "notapackage", "com..broken", "com example").forEach { pkg ->
            assertEquals(
                "package '$pkg'",
                RequestValidation.Invalid(RequestInvalidReason.INVALID_TARGET_PACKAGE),
                RequestValidator.validate(1L, 5L, pkg, 15, 1_000L),
            )
        }
    }

    @Test
    fun zeroAndNegativeDurationAreRejected() {
        assertFalse(RequestValidator.isValid(1L, 5L, "com.example.youtube", 0, 1_000L))
        assertFalse(RequestValidator.isValid(1L, 5L, "com.example.youtube", -15, 1_000L))
    }

    @Test
    fun durationBoundsAreEnforced() {
        assertTrue(RequestValidator.isValid(1L, 5L, "com.example.youtube", RequestLimits.MIN_MINUTES, 1_000L))
        assertTrue(RequestValidator.isValid(1L, 5L, "com.example.youtube", RequestLimits.MAX_MINUTES, 1_000L))
        assertFalse(RequestValidator.isValid(1L, 5L, "com.example.youtube", RequestLimits.MAX_MINUTES + 1, 1_000L))
    }

    @Test
    fun expiryMustBeInTheFuture() {
        assertEquals(
            RequestValidation.Invalid(RequestInvalidReason.INVALID_EXPIRY),
            RequestValidator.validate(1L, 5L, "com.example.youtube", 15, createdAt = 1_000L, expiresAt = 1_000L),
        )
        assertEquals(
            RequestValidation.Valid,
            RequestValidator.validate(1L, 5L, "com.example.youtube", 15, createdAt = 1_000L, expiresAt = 2_000L),
        )
    }

    // ---- deduplication ------------------------------------------------------

    private fun request(
        id: Long = 1L,
        childId: Long = 5L,
        pkg: String = "com.example.youtube",
        status: RequestStatus = RequestStatus.PENDING,
        createdAt: Long = 10_000L,
    ) = ParentRequest(
        id = id,
        accountId = 1L,
        childId = childId,
        targetPackageName = pkg,
        requestedDurationMinutes = 15,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        deduplicationKey = RequestDeduplication.keyFor(1L, childId, RequestType.EXTRA_TIME, pkg),
    )

    @Test
    fun theKeyIsStableAndIgnoresDuration() {
        val a = RequestDeduplication.keyFor(1L, 5L, RequestType.EXTRA_TIME, "com.example.youtube")
        val b = RequestDeduplication.keyFor(1L, 5L, RequestType.EXTRA_TIME, "com.example.youtube")
        assertEquals(a, b)
    }

    @Test
    fun theKeyDistinguishesChildAndApp() {
        val base = RequestDeduplication.keyFor(1L, 5L, RequestType.EXTRA_TIME, "com.example.youtube")
        assertTrue(base != RequestDeduplication.keyFor(1L, 6L, RequestType.EXTRA_TIME, "com.example.youtube"))
        assertTrue(base != RequestDeduplication.keyFor(1L, 5L, RequestType.EXTRA_TIME, "com.example.games"))
        assertTrue(base != RequestDeduplication.keyFor(2L, 5L, RequestType.EXTRA_TIME, "com.example.youtube"))
    }

    @Test
    fun aRepeatedTapInsideTheWindowIsADuplicate() {
        val existing = request(createdAt = 10_000L)
        assertTrue(
            RequestDeduplication.isDuplicateActive(existing, existing.deduplicationKey, candidateCreatedAt = 10_500L),
        )
        assertTrue(
            RequestDeduplication.isDuplicateActive(
                existing,
                existing.deduplicationKey,
                candidateCreatedAt = 10_000L + RequestLimits.DEDUP_WINDOW_MS,
            ),
        )
    }

    @Test
    fun aLaterIdenticalRequestIsLegitimate() {
        val existing = request(createdAt = 10_000L)
        assertFalse(
            RequestDeduplication.isDuplicateActive(
                existing,
                existing.deduplicationKey,
                candidateCreatedAt = 10_000L + RequestLimits.DEDUP_WINDOW_MS + 1,
            ),
        )
    }

    @Test
    fun aResolvedRequestNeverBlocksALaterOne() {
        val approved = request(status = RequestStatus.APPROVED)
        assertFalse(
            RequestDeduplication.isDuplicateActive(approved, approved.deduplicationKey, candidateCreatedAt = 10_100L),
        )
    }

    @Test
    fun differentChildrenOrAppsAreNeverDuplicates() {
        val existing = request(childId = 5L, pkg = "com.example.youtube")
        val otherChild = RequestDeduplication.keyFor(1L, 6L, RequestType.EXTRA_TIME, "com.example.youtube")
        val otherApp = RequestDeduplication.keyFor(1L, 5L, RequestType.EXTRA_TIME, "com.example.games")
        assertFalse(RequestDeduplication.isDuplicateActive(existing, otherChild, candidateCreatedAt = 10_100L))
        assertFalse(RequestDeduplication.isDuplicateActive(existing, otherApp, candidateCreatedAt = 10_100L))
    }
}
