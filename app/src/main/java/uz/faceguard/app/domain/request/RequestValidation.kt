package uz.faceguard.app.domain.request

/**
 * Pure validation and deduplication for parent requests.
 *
 * Malformed requests must never reach persistence or the UI as apparently valid
 * requests, and repeated creation must not produce duplicates — while genuinely
 * distinct, later requests must stay possible.
 */
object RequestValidator {

    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun validate(
        accountId: Long,
        childId: Long,
        targetPackageName: String?,
        requestedDurationMinutes: Int,
        createdAt: Long,
        expiresAt: Long? = null,
    ): RequestValidation {
        if (accountId <= 0L) return RequestValidation.Invalid(RequestInvalidReason.INVALID_ACCOUNT)
        if (childId <= 0L) return RequestValidation.Invalid(RequestInvalidReason.INVALID_CHILD)
        val pkg = targetPackageName?.trim().orEmpty()
        if (pkg.isEmpty() || !PACKAGE_PATTERN.matches(pkg)) {
            return RequestValidation.Invalid(RequestInvalidReason.INVALID_TARGET_PACKAGE)
        }
        if (requestedDurationMinutes < RequestLimits.MIN_MINUTES ||
            requestedDurationMinutes > RequestLimits.MAX_MINUTES
        ) {
            return RequestValidation.Invalid(RequestInvalidReason.INVALID_DURATION)
        }
        if (expiresAt != null && expiresAt <= createdAt) {
            return RequestValidation.Invalid(RequestInvalidReason.INVALID_EXPIRY)
        }
        return RequestValidation.Valid
    }

    fun isValid(
        accountId: Long,
        childId: Long,
        targetPackageName: String?,
        requestedDurationMinutes: Int,
        createdAt: Long,
        expiresAt: Long? = null,
    ): Boolean = validate(accountId, childId, targetPackageName, requestedDurationMinutes, createdAt, expiresAt) ==
        RequestValidation.Valid
}

/** Identity + duplicate rules for request creation. */
object RequestDeduplication {

    /**
     * Stable identity of "the same request": same account, child, type and target
     * app. Deliberately excludes the duration so a repeated tap does not slip
     * through with a slightly different value.
     */
    fun keyFor(
        accountId: Long,
        childId: Long,
        requestType: RequestType,
        targetPackageName: String,
    ): String = "$requestType:$accountId:$childId:${targetPackageName.trim()}"

    /**
     * True when [existing] is an active request that the candidate (created at
     * [candidateCreatedAt]) must not duplicate: same key, still pending, and
     * created no more than [RequestLimits.DEDUP_WINDOW_MS] earlier. A later
     * request outside the window is legitimate.
     */
    fun isDuplicateActive(
        existing: ParentRequest,
        candidateKey: String,
        candidateCreatedAt: Long,
    ): Boolean {
        if (!existing.isPending) return false
        if (existing.deduplicationKey != candidateKey) return false
        val age = candidateCreatedAt - existing.createdAt
        return age >= 0L && age <= RequestLimits.DEDUP_WINDOW_MS
    }
}
