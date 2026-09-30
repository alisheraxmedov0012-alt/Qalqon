package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.data.db.UsageSnapshotCheckpointDao
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpoint
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpointRepository
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-6: Room-backed snapshot checkpoints.
 *
 * Every operation is account + child scoped, and a save only ever replaces a row with the
 * *same* identity (account, child, source, window, package), so no account, child, package
 * or window can overwrite another's baseline. Rows that cannot describe a real baseline are
 * skipped rather than trusted (see the mapper).
 */
@Singleton
class UsageSnapshotCheckpointRepositoryImpl @Inject constructor(
    private val dao: UsageSnapshotCheckpointDao,
) : UsageSnapshotCheckpointRepository {

    override suspend fun checkpoint(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
        packageName: String,
    ): UsageSnapshotCheckpoint? =
        dao.checkpoint(
            accountId = accountId,
            childId = childId,
            source = source.name,
            windowStartMs = range.startTimeMs,
            windowEndMs = range.endTimeMs,
            packageName = packageName.trim(),
        )?.toDomainCheckpoint()

    override suspend fun checkpointsForWindow(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
    ): List<UsageSnapshotCheckpoint> =
        dao.checkpointsForWindow(
            accountId = accountId,
            childId = childId,
            source = source.name,
            windowStartMs = range.startTimeMs,
            windowEndMs = range.endTimeMs,
        ).mapNotNull { it.toDomainCheckpoint() }

    override suspend fun save(checkpoints: List<UsageSnapshotCheckpoint>) {
        if (checkpoints.isEmpty()) return
        dao.upsertAll(checkpoints.map { it.toEntity() })
    }

    override suspend fun delete(
        accountId: Long,
        childId: Long,
        source: UsageSourceId,
        range: UsageRange,
        packageName: String,
    ) {
        dao.delete(
            accountId = accountId,
            childId = childId,
            source = source.name,
            windowStartMs = range.startTimeMs,
            windowEndMs = range.endTimeMs,
            packageName = packageName.trim(),
        )
    }

    override suspend fun deleteForChild(accountId: Long, childId: Long) {
        dao.deleteForChild(accountId, childId)
    }

    override suspend fun deleteForAccount(accountId: Long) {
        dao.deleteForAccount(accountId)
    }
}
