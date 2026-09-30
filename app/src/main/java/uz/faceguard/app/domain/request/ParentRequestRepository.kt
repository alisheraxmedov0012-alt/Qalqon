package uz.faceguard.app.domain.request

import kotlinx.coroutines.flow.Flow

/**
 * Durable, account-scoped parent requests.
 *
 * All mutations are ownership-checked and transition-checked by the
 * implementation *inside the database transaction*, so the UI cannot force an
 * illegal state even if it is out of date.
 */
interface ParentRequestRepository {

    /** Pending requests of one account, newest first, bounded. */
    fun observePending(accountId: Long): Flow<List<ParentRequest>>

    /** Pending + resolved history of one account, newest first, bounded. */
    fun observeForAccount(accountId: Long): Flow<List<ParentRequest>>

    fun observePendingCount(accountId: Long): Flow<Int>

    fun observePendingForChild(accountId: Long, childId: Long): Flow<List<ParentRequest>>

    suspend fun byId(accountId: Long, requestId: Long): ParentRequest?

    /**
     * Validates and persists a new request. Rejects malformed input and collapses
     * a repeated active request into [RequestCreationResult.Duplicate].
     */
    suspend fun create(request: ParentRequest): RequestCreationResult

    suspend fun approve(
        accountId: Long,
        requestId: Long,
        approvedDurationMinutes: Int?,
        now: Long,
    ): RequestResolutionResult

    suspend fun reject(accountId: Long, requestId: Long, now: Long): RequestResolutionResult

    suspend fun cancel(accountId: Long, requestId: Long, now: Long): RequestResolutionResult

    /**
     * Moves *this account's* pending requests whose `expiresAt` has passed to
     * EXPIRED. Idempotent, account-scoped, and safe to call on access instead of
     * running a permanent poller. Returns the number actually expired.
     *
     * Expiration is evaluated when records are accessed, so it is not real-time
     * while the process is stopped (documented limitation, see the README).
     */
    suspend fun expireStale(accountId: Long, now: Long): Int

    /** Account-scoped history cap used by the queries above. */
    companion object {
        const val HISTORY_LIMIT = 50
    }
}
