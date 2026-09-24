package uz.faceguard.app.requests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.request.RequestType
import uz.faceguard.app.feature.requests.requestRows
import uz.faceguard.app.feature.requests.requestStatusLabelRes

/**
 * Phase 11: parent-facing presentation of a request (status labels, rows built
 * from stable ids, app-label fallback). Pure JVM.
 */
class RequestPresentationTest {

    @Test
    fun everyStatusHasALocalizedLabel() {
        assertEquals(R.string.request_status_pending, requestStatusLabelRes(RequestStatus.PENDING))
        assertEquals(R.string.request_status_approved, requestStatusLabelRes(RequestStatus.APPROVED))
        assertEquals(R.string.request_status_rejected, requestStatusLabelRes(RequestStatus.REJECTED))
        assertEquals(R.string.request_status_expired, requestStatusLabelRes(RequestStatus.EXPIRED))
        assertEquals(R.string.request_status_cancelled, requestStatusLabelRes(RequestStatus.CANCELLED))
    }

    private fun request(id: Long = 1L, childId: Long = 5L, pkg: String = "com.example.youtube") = ParentRequest(
        id = id,
        accountId = 1L,
        childId = childId,
        targetPackageName = pkg,
        requestType = RequestType.EXTRA_TIME,
        requestedDurationMinutes = 15,
        status = RequestStatus.PENDING,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        deduplicationKey = "EXTRA_TIME:1:$childId:$pkg",
    )

    @Test
    fun rowsResolveTheChildNameByStableId() {
        val rows = requestRows(
            requests = listOf(request(childId = 5L), request(id = 2L, childId = 6L, pkg = "com.example.games")),
            childrenById = mapOf(5L to "Vali", 6L to "Ali"),
            appLabel = { it },
        )

        assertEquals(2, rows.size)
        assertEquals("Vali", rows[0].childName)
        assertEquals("Ali", rows[1].childName)
    }

    @Test
    fun aDeletedChildDoesNotInventAName() {
        val rows = requestRows(listOf(request(childId = 99L)), childrenById = emptyMap(), appLabel = { it })
        assertNull("a deleted child must not borrow another child's name", rows.first().childName)
    }

    @Test
    fun theAppLabelComesFromTheResolverAndFallsBackSafely() {
        val rows = requestRows(
            requests = listOf(request(pkg = "com.example.youtube"), request(id = 2L, pkg = "com.example.gone")),
            childrenById = emptyMap(),
            appLabel = { pkg -> if (pkg.endsWith("youtube")) "YouTube" else "Unknown app" },
        )

        assertEquals("YouTube", rows[0].appLabel)
        assertEquals("Unknown app", rows[1].appLabel)
    }

    @Test
    fun rowsPreserveTheRequestIdentityAndStatus() {
        val source = request(id = 7L)
        val row = requestRows(listOf(source), emptyMap(), appLabel = { it }).first()
        assertEquals(7L, row.request.id)
        assertEquals(RequestStatus.PENDING, row.request.status)
        assertEquals(15, row.request.requestedDurationMinutes)
    }
}
