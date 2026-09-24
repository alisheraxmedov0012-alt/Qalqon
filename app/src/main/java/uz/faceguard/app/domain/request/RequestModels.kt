package uz.faceguard.app.domain.request

/**
 * Phase 11: parent requests (a durable object that needs a parent decision).
 *
 * Kept deliberately separate from protection events (what happened to
 * protection?), from notifications (how was the parent informed?) and from the
 * Phase 4 screen-time engine (which does not exist yet). A request is an
 * *authorization record only*: approving it never fabricates usage, never
 * touches usage counters and never bypasses the PolicyEvaluator.
 */

/** Only the request kind that is genuinely implementable without Phase 4. */
enum class RequestType {
    /** "Child asks for more time on a blocked app" — duration is a *request*, not usage. */
    EXTRA_TIME,
}

/** Explicit request lifecycle. See [RequestStateMachine] for the legal transitions. */
enum class RequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED,
}

/** Where a request came from (stable, non-user-facing). */
enum class RequestSource {
    /** The child tapped "request extra time" on the protection overlay. */
    CHILD_OVERLAY,
}

/** Domain bounds for a requested duration (minutes). */
object RequestLimits {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 24 * 60

    /** Default offered by the child-facing surface. */
    const val DEFAULT_REQUEST_MINUTES = 15

    /**
     * Two otherwise-identical requests created inside this window are treated as
     * the same request (repeated taps / recomposition / duplicate collectors).
     * Outside the window a new legitimate request is allowed.
     */
    const val DEDUP_WINDOW_MS = 60_000L
}

/**
 * A durable parent request. `requestedDurationMinutes` is what the child asked
 * for; `approvedDurationMinutes` is what the parent authorized. Neither is
 * usage: no consumed/remaining time is stored or derived here.
 */
data class ParentRequest(
    val id: Long = 0,
    val accountId: Long,
    val childId: Long,
    val targetPackageName: String,
    val requestType: RequestType = RequestType.EXTRA_TIME,
    val requestedDurationMinutes: Int,
    val approvedDurationMinutes: Int? = null,
    val status: RequestStatus = RequestStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val expiresAt: Long? = null,
    val resolvedAt: Long? = null,
    val resolutionReason: String? = null,
    val source: RequestSource = RequestSource.CHILD_OVERLAY,
    val deduplicationKey: String,
) {
    val isPending: Boolean get() = status == RequestStatus.PENDING
    val isResolved: Boolean get() = status != RequestStatus.PENDING
}

/** What the parent decided about a pending request. */
enum class RequestDecision { APPROVE, REJECT }

/** Result of trying to create a request (validation + deduplication). */
sealed interface RequestCreationResult {
    data class Created(val request: ParentRequest) : RequestCreationResult

    /** An identical, still-active request already exists (see [RequestLimits.DEDUP_WINDOW_MS]). */
    data class Duplicate(val existing: ParentRequest) : RequestCreationResult

    /** The request is malformed or not allowed; [reason] is for logs, not the UI. */
    data class Rejected(val reason: String) : RequestCreationResult
}

/** Result of trying to resolve a pending request. */
sealed interface RequestResolutionResult {
    data class Resolved(val request: ParentRequest) : RequestResolutionResult

    /** The request was already resolved, expired or is not this account's. */
    data class NotPending(val currentStatus: RequestStatus?) : RequestResolutionResult

    data class NotFound(val reason: String) : RequestResolutionResult
}

/** Why a request validation failed (internal; the UI maps to localized text). */
enum class RequestInvalidReason {
    INVALID_ACCOUNT,
    INVALID_CHILD,
    INVALID_TARGET_PACKAGE,
    INVALID_DURATION,
    INVALID_EXPIRY,
}

/** Pure validation result. */
sealed interface RequestValidation {
    data object Valid : RequestValidation
    data class Invalid(val reason: RequestInvalidReason) : RequestValidation
}
