package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ChildScreenTimeLimitDao
import uz.faceguard.app.data.db.ChildScreenTimeLimitEntity
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimit
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeLimits

/**
 * Phase 4 Step 1C: reads the TOTAL / CATEGORY limits that Step 1A already stores in
 * `child_screen_time_limits`.
 *
 * No new table, no new column and no new DAO — the DAO has existed since v7; this simply
 * exposes it to the domain, so the evaluator never touches Room. `limitMinutes` is passed
 * through untouched, including an out-of-range value, because a corrupt limit must stay
 * visible to the evaluator instead of being silently repaired here.
 *
 * A row whose `scope` or `category` cannot be interpreted is skipped: an unreadable row
 * means "no limit configured for that scope", which yields the safe unlimited result rather
 * than a limit nobody can explain.
 */
@Singleton
class ScreenTimeLimitRepositoryImpl @Inject constructor(
    private val dao: ChildScreenTimeLimitDao,
    /** Wall clock for the row's bookkeeping timestamp; the limit itself is not clock-based. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ScreenTimeLimitRepository {

    override suspend fun limits(accountId: Long, childId: Long): List<ScreenTimeLimit> =
        dao.limits(accountId, childId).mapNotNull { it.toDomainLimit(accountId, childId) }

    override fun observeLimits(accountId: Long, childId: Long): Flow<List<ScreenTimeLimit>> =
        dao.observeLimits(accountId, childId).map { rows ->
            rows.mapNotNull { it.toDomainLimit(accountId, childId) }
        }

    override suspend fun upsert(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory?,
        limitMinutes: Int,
    ) {
        require(accountId > 0L) { "accountId must be positive, was $accountId" }
        require(childId > 0L) { "childId must be positive, was $childId" }
        require(scope != LimitScope.APP) {
            "per-app limits live in child_app_policies, not in a screen-time limit row"
        }
        require((scope == LimitScope.CATEGORY) == (category != null)) {
            "a TOTAL limit has no category and a CATEGORY limit must have one (scope=$scope)"
        }
        // The domain rule stays authoritative: an invalid limit is rejected here rather than
        // persisted, so no caller can store something the evaluator could not honour.
        require(ScreenTimeLimits.isAccepted(limitMinutes)) {
            "limitMinutes must be within 0..${ScreenTimeLimits.MINUTES_PER_DAY}, was $limitMinutes"
        }

        dao.upsert(
            ChildScreenTimeLimitEntity(
                accountId = accountId,
                childId = childId,
                scope = scope.name,
                category = category?.name ?: TOTAL_CATEGORY,
                limitMinutes = limitMinutes,
                updatedAt = clock(),
            ),
        )
    }

    override suspend fun delete(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory?,
    ) {
        if (scope == LimitScope.APP) return
        val storedCategory = when (scope) {
            LimitScope.TOTAL -> TOTAL_CATEGORY
            else -> category?.name ?: return
        }
        dao.delete(accountId, childId, scope.name, storedCategory)
    }

    override suspend fun limit(
        accountId: Long,
        childId: Long,
        scope: LimitScope,
        category: AppCategory?,
    ): ScreenTimeLimit? {
        if (scope == LimitScope.APP) return null
        // Mirrors the storage convention exactly: TOTAL is stored with an empty category,
        // CATEGORY with its AppCategory name.
        val storedCategory = when (scope) {
            LimitScope.TOTAL -> TOTAL_CATEGORY
            else -> category?.name ?: return null
        }
        return dao.limit(accountId, childId, scope.name, storedCategory)
            ?.toDomainLimit(accountId, childId)
    }

    private fun ChildScreenTimeLimitEntity.toDomainLimit(
        accountId: Long,
        childId: Long,
    ): ScreenTimeLimit? {
        val parsedScope = runCatching { LimitScope.valueOf(scope) }.getOrNull() ?: return null
        if (parsedScope == LimitScope.APP) return null
        val parsedCategory = when (parsedScope) {
            LimitScope.TOTAL -> null
            else -> runCatching { AppCategory.valueOf(category) }.getOrNull() ?: return null
        }
        return ScreenTimeLimit(
            accountId = accountId,
            childId = childId,
            scope = parsedScope,
            category = parsedCategory,
            limitMinutes = limitMinutes,
        )
    }

    private companion object {
        /** The existing storage convention for a TOTAL limit. */
        const val TOTAL_CATEGORY = ""
    }
}
