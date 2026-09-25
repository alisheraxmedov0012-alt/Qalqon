package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.data.db.ChildScreenTimeLimitDao
import uz.faceguard.app.data.db.ChildScreenTimeLimitEntity
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.LimitScope
import uz.faceguard.app.domain.screentime.ScreenTimeLimit
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository

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
) : ScreenTimeLimitRepository {

    override suspend fun limits(accountId: Long, childId: Long): List<ScreenTimeLimit> =
        dao.limits(accountId, childId).mapNotNull { it.toDomainLimit(accountId, childId) }

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
