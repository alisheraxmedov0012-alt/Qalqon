package uz.faceguard.app.domain.policy

import kotlinx.coroutines.flow.Flow

/**
 * Per-child app policies (ALLOW / LIMIT / BLOCK).
 *
 * The legacy `ProtectedAppsRepository` keeps owning the *global*
 * "is this app protected at all" catalog so existing behaviour and UI are
 * untouched. This repository owns the newer, child-scoped overrides on top.
 */
interface ChildAppPolicyRepository {

    fun observePolicies(accountId: Long, childId: Long): Flow<List<AppPolicy>>

    suspend fun policyFor(accountId: Long, childId: Long, packageName: String): AppPolicy?

    suspend fun upsert(accountId: Long, childId: Long, policy: AppPolicy)

    suspend fun delete(accountId: Long, childId: Long, packageName: String)

    suspend fun deleteAllForChild(accountId: Long, childId: Long)
}
