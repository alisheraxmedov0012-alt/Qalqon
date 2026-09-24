package uz.faceguard.app.domain.request

/**
 * The request lifecycle, expressed as pure rules so the persistence layer and
 * the UI cannot disagree about what is legal.
 *
 * PENDING -> APPROVED | REJECTED | EXPIRED | CANCELLED
 * Every resolved state is terminal: a request is resolved at most once, and no
 * resolved state can go back to PENDING (or to any other state).
 */
object RequestStateMachine {

    /** The only state a parent decision may start from. */
    fun canResolve(status: RequestStatus): Boolean = status == RequestStatus.PENDING

    fun targetFor(decision: RequestDecision): RequestStatus = when (decision) {
        RequestDecision.APPROVE -> RequestStatus.APPROVED
        RequestDecision.REJECT -> RequestStatus.REJECTED
    }

    /** The status a parent decision would produce, or null when it is invalid. */
    fun resolve(current: RequestStatus, decision: RequestDecision): RequestStatus? =
        if (canResolve(current)) targetFor(decision) else null

    /**
     * Expiration is only meaningful for a pending request and is idempotent: an
     * already-resolved request stays as it is.
     */
    fun expire(current: RequestStatus): RequestStatus? =
        if (canResolve(current)) RequestStatus.EXPIRED else null

    /** Cancellation follows the same rule as the other terminal transitions. */
    fun cancel(current: RequestStatus): RequestStatus? =
        if (canResolve(current)) RequestStatus.CANCELLED else null

    /** True when [current] may move to [next]. Used by tests and the repository. */
    fun isLegalTransition(current: RequestStatus, next: RequestStatus): Boolean = when (next) {
        RequestStatus.PENDING -> false
        RequestStatus.APPROVED -> current == RequestStatus.PENDING
        RequestStatus.REJECTED -> current == RequestStatus.PENDING
        RequestStatus.EXPIRED -> current == RequestStatus.PENDING
        RequestStatus.CANCELLED -> current == RequestStatus.PENDING
    }
}
