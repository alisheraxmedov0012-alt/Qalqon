package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ParentRequestDao
import uz.faceguard.app.data.db.ParentRequestEntity
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestCreationResult
import uz.faceguard.app.domain.request.RequestDeduplication
import uz.faceguard.app.domain.request.RequestResolutionResult
import uz.faceguard.app.domain.request.RequestSource
import uz.faceguard.app.domain.request.RequestStateMachine
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.request.RequestType
import uz.faceguard.app.domain.request.RequestValidation
import uz.faceguard.app.domain.request.RequestValidator
import uz.faceguard.app.domain.repository.ChildProfileRepository

/**
 * Room-backed, account-scoped parent requests.
 *
 * Validation, ownership and transition rules are enforced *here* (and again in
 * SQL), not merely by disabling UI buttons: [resolve] only ever updates a
 * still-pending row that belongs to the given account, so duplicate or
 * concurrent decisions cannot produce contradictory state.
 *
 * Nothing in this class measures time spent: a request is an authorization
 * record only (Phase 4 owns usage).
 */
@Singleton
class ParentRequestRepositoryImpl @Inject constructor(
    private val dao: ParentRequestDao,
    private val childProfileRepository: ChildProfileRepository,
) : ParentRequestRepository {

    override fun observePending(accountId: Long): Flow<List<ParentRequest>> =
        dao.observePending(accountId, ParentRequestRepository.HISTORY_LIMIT).map { rows -> rows.map { it.toDomain() } }

    override fun observeForAccount(accountId: Long): Flow<List<ParentRequest>> =
        dao.observeForAccount(accountId, ParentRequestRepository.HISTORY_LIMIT).map { rows -> rows.map { it.toDomain() } }

    override fun observePendingCount(accountId: Long): Flow<Int> = dao.observePendingCount(accountId)

    override fun observePendingForChild(accountId: Long, childId: Long): Flow<List<ParentRequest>> =
        dao.observePendingForChild(accountId, childId, ParentRequestRepository.HISTORY_LIMIT)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun byId(accountId: Long, requestId: Long): ParentRequest? =
        dao.byId(accountId, requestId)?.toDomain()

    override suspend fun create(request: ParentRequest): RequestCreationResult {
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

        // Stable child id, never the display name; the child must belong to the account.
        val childExists = childProfileRepository.observeChildren(request.accountId)
            .first()
            .any { it.id == request.childId }
        if (!childExists) return RequestCreationResult.Rejected("child_not_in_account")

        val key = request.deduplicationKey.ifBlank {
            RequestDeduplication.keyFor(
                accountId = request.accountId,
                childId = request.childId,
                requestType = request.requestType,
                targetPackageName = request.targetPackageName,
            )
        }
        val active = dao.activeByKey(request.accountId, key)?.toDomain()
        if (active != null && RequestDeduplication.isDuplicateActive(active, key, request.createdAt)) {
            return RequestCreationResult.Duplicate(active)
        }

        val toInsert = request.copy(deduplicationKey = key)
        val id = dao.insert(toInsert.toEntity())
        return RequestCreationResult.Created(toInsert.copy(id = id))
    }

    override suspend fun approve(
        accountId: Long,
        requestId: Long,
        approvedDurationMinutes: Int?,
        now: Long,
    ): RequestResolutionResult {
        val current = dao.byId(accountId, requestId)?.toDomain()
            ?: return RequestResolutionResult.NotFound("request not found for this account")

        val authorized = approvedDurationMinutes ?: current.requestedDurationMinutes
        if (!RequestValidator.isValid(
                accountId = accountId,
                childId = current.childId,
                targetPackageName = current.targetPackageName,
                requestedDurationMinutes = authorized,
                createdAt = current.createdAt,
            )
        ) {
            return RequestResolutionResult.NotFound("invalid approved duration")
        }
        return resolve(accountId, requestId, RequestStatus.APPROVED, authorized, now, "parent_approved")
    }

    override suspend fun reject(accountId: Long, requestId: Long, now: Long): RequestResolutionResult =
        resolve(accountId, requestId, RequestStatus.REJECTED, null, now, "parent_rejected")

    override suspend fun cancel(accountId: Long, requestId: Long, now: Long): RequestResolutionResult =
        resolve(accountId, requestId, RequestStatus.CANCELLED, null, now, "cancelled")

    override suspend fun expireStale(accountId: Long, now: Long): Int = dao.expireStale(accountId, now)

    private suspend fun resolve(
        accountId: Long,
        requestId: Long,
        target: RequestStatus,
        approvedMinutes: Int?,
        now: Long,
        reason: String,
    ): RequestResolutionResult {
        val current = dao.byId(accountId, requestId)?.toDomain()
            ?: return RequestResolutionResult.NotFound("request not found for this account")
        if (!RequestStateMachine.isLegalTransition(current.status, target)) {
            return RequestResolutionResult.NotPending(current.status)
        }
        val updated = dao.resolve(
            accountId = accountId,
            id = requestId,
            newStatus = target.name,
            approvedMinutes = approvedMinutes,
            now = now,
            reason = reason,
        )
        val after = dao.byId(accountId, requestId)?.toDomain()
        return if (updated == 1 && after != null) {
            RequestResolutionResult.Resolved(after)
        } else {
            RequestResolutionResult.NotPending(after?.status)
        }
    }
}

// ---------------------------------------------------------------------------
// Entity <-> domain mapping (Room types never leave the data layer)
// ---------------------------------------------------------------------------

internal fun ParentRequestEntity.toDomain() = ParentRequest(
    id = id,
    accountId = accountId,
    childId = childId,
    targetPackageName = targetPackageName,
    requestType = runCatching { RequestType.valueOf(requestType) }.getOrDefault(RequestType.EXTRA_TIME),
    requestedDurationMinutes = requestedDurationMinutes,
    approvedDurationMinutes = approvedDurationMinutes,
    status = runCatching { RequestStatus.valueOf(status) }.getOrDefault(RequestStatus.EXPIRED),
    createdAt = createdAt,
    updatedAt = updatedAt,
    expiresAt = expiresAt,
    resolvedAt = resolvedAt,
    resolutionReason = resolutionReason,
    source = runCatching { RequestSource.valueOf(source) }.getOrDefault(RequestSource.CHILD_OVERLAY),
    deduplicationKey = deduplicationKey,
)

internal fun ParentRequest.toEntity() = ParentRequestEntity(
    id = id,
    accountId = accountId,
    childId = childId,
    targetPackageName = targetPackageName,
    requestType = requestType.name,
    requestedDurationMinutes = requestedDurationMinutes,
    approvedDurationMinutes = approvedDurationMinutes,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    expiresAt = expiresAt,
    resolvedAt = resolvedAt,
    resolutionReason = resolutionReason,
    source = source.name,
    deduplicationKey = deduplicationKey,
)
