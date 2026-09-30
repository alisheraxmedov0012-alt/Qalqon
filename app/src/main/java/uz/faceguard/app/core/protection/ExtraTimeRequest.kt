package uz.faceguard.app.core.protection

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestCreationResult
import uz.faceguard.app.domain.request.RequestDeduplication
import uz.faceguard.app.domain.request.RequestLimits
import uz.faceguard.app.domain.request.RequestType

/**
 * Phase 7.3: the outcome of a child's "request extra time" attempt.
 *
 * It is deliberately *not* a protection state: a request is an authorization
 * record only. [Created] means a PENDING request was persisted — it grants
 * nothing, never disables protection and never bypasses the parent PIN or the
 * policy evaluator.
 */
sealed interface ExtraTimeRequestResult {
    /** A new PENDING request was persisted (see [ParentRequest.isPending]). */
    data class Created(val request: ParentRequest) : ExtraTimeRequestResult

    /** An identical, still-active request already exists; nothing was written. */
    data object Duplicate : ExtraTimeRequestResult

    /**
     * The tap could not become a request because the runtime context is incomplete
     * (not signed in, no blocked app, or no recognised child). No record is written.
     */
    data object Unavailable : ExtraTimeRequestResult

    /** The request was malformed or persistence failed; nothing is guaranteed. */
    data object Failed : ExtraTimeRequestResult
}

/**
 * Phase 7.3: the single seam between the child-facing "request extra time" control
 * and the existing request machinery.
 *
 * It does exactly one thing — turn a tap into a durable request through the
 * existing [ParentRequestRepository] — reusing the Phase 11 domain rules **as
 * they are**:
 *  - the request is created via the repository, which validates the child's
 *    ownership, the package and the duration and enforces the existing
 *    deduplication window ([RequestDeduplication]) so a repeated tap cannot
 *    produce duplicates;
 *  - the persisted request stays PENDING; approval remains the parent's decision
 *    (the repository's state machine owns the transitions).
 *
 * It holds no reference to protection: it cannot disable protection, cannot
 * bypass a PIN and cannot alter any policy. A repository failure is folded into
 * [ExtraTimeRequestResult.Failed] instead of propagating, so a request error can
 * never take the protection engine (or the process) down with it.
 *
 * This seam exists because the request path previously lived inline in the
 * runtime and was therefore only reachable from a running protection session —
 * not unit-testable and not failure-isolated.
 */
@Singleton
class ExtraTimeRequester @Inject constructor(
    private val requestRepository: ParentRequestRepository,
    private val clock: () -> Long,
) {

    /**
     * Creates a PENDING extra-time request for [childId] on [packageName] in
     * [accountId]. Never throws; see [ExtraTimeRequestResult] for the outcomes.
     */
    suspend fun request(
        accountId: Long?,
        childId: Long?,
        packageName: String?,
    ): ExtraTimeRequestResult {
        if (accountId == null || accountId <= 0L) return ExtraTimeRequestResult.Unavailable
        if (childId == null || childId <= 0L) return ExtraTimeRequestResult.Unavailable
        val target = packageName?.trim().orEmpty()
        if (target.isEmpty()) return ExtraTimeRequestResult.Unavailable

        val now = clock()
        val request = ParentRequest(
            accountId = accountId,
            childId = childId,
            targetPackageName = target,
            requestType = RequestType.EXTRA_TIME,
            requestedDurationMinutes = RequestLimits.DEFAULT_REQUEST_MINUTES,
            createdAt = now,
            updatedAt = now,
            deduplicationKey = RequestDeduplication.keyFor(
                accountId = accountId,
                childId = childId,
                requestType = RequestType.EXTRA_TIME,
                targetPackageName = target,
            ),
        )

        return runCatching { requestRepository.create(request) }.fold(
            onSuccess = { result ->
                when (result) {
                    is RequestCreationResult.Created -> ExtraTimeRequestResult.Created(result.request)
                    is RequestCreationResult.Duplicate -> ExtraTimeRequestResult.Duplicate
                    is RequestCreationResult.Rejected -> ExtraTimeRequestResult.Failed
                }
            },
            onFailure = { ExtraTimeRequestResult.Failed },
        )
    }
}
