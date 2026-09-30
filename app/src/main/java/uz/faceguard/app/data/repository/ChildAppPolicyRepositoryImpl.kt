package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ChildAppPolicyDao
import uz.faceguard.app.data.db.ChildAppPolicyEntity
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository

/**
 * Room-backed child app policies. Mapping Room entity <-> domain `AppPolicy`
 * stays here so the domain layer never sees Room types.
 */
@Singleton
class ChildAppPolicyRepositoryImpl @Inject constructor(
    private val dao: ChildAppPolicyDao,
) : ChildAppPolicyRepository {

    override fun observePolicies(accountId: Long, childId: Long): Flow<List<AppPolicy>> =
        dao.observePolicies(accountId, childId).map { rows ->
            rows.map { row -> row.toDomainPolicy() }
        }

    override suspend fun policyFor(accountId: Long, childId: Long, packageName: String): AppPolicy? =
        dao.getPolicy(accountId, childId, packageName)?.toDomainPolicy()

    override suspend fun upsert(accountId: Long, childId: Long, policy: AppPolicy) {
        val existing = dao.getPolicy(accountId, childId, policy.packageName)
        val now = System.currentTimeMillis()
        dao.upsert(
            ChildAppPolicyEntity(
                accountId = accountId,
                childId = childId,
                packageName = policy.packageName,
                mode = policy.mode.name,
                action = policy.action.name,
                dailyLimitMinutes = policy.dailyLimitMinutes,
                activationDelayMs = policy.activationDelayMs,
                recoveryDelayMs = policy.recoveryDelayMs,
                enabled = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun delete(accountId: Long, childId: Long, packageName: String) {
        dao.delete(accountId, childId, packageName)
    }

    override suspend fun deleteAllForChild(accountId: Long, childId: Long) {
        dao.deleteForChild(accountId, childId)
    }
}
