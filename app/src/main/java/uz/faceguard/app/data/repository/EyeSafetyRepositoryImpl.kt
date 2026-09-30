package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ChildEyeSafetyDao
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository

/**
 * Phase 6 Step 3: Room-backed eye-safety configuration.
 *
 * Every statement is scoped by `accountId` + `childId`, and the mapping keeps Room entities out of
 * the domain layer. There is no caching and no state of its own: this is storage for the parent's
 * choices, and the reading of it during protection is a later step.
 *
 * A missing row stays missing. [config] returns `null` and [observeConfig] emits `null` — the
 * absence is the answer — and no default row is ever written, so "unconfigured" can never be
 * mistaken for a stored `enabled = false`.
 */
@Singleton
class EyeSafetyRepositoryImpl @Inject constructor(
    private val dao: ChildEyeSafetyDao,
) : EyeSafetyRepository {

    override suspend fun config(accountId: Long, childId: Long): ChildEyeSafetyConfig? =
        dao.config(accountId, childId)?.toDomainEyeSafety()

    override fun observeConfig(accountId: Long, childId: Long): Flow<ChildEyeSafetyConfig?> =
        dao.observeConfig(accountId, childId).map { entity -> entity?.toDomainEyeSafety() }

    override suspend fun save(config: ChildEyeSafetyConfig) {
        dao.upsert(config.toEntity())
    }

    override suspend fun delete(accountId: Long, childId: Long) {
        dao.delete(accountId, childId)
    }
}
