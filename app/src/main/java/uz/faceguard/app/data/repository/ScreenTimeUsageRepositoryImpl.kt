package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.DailyAppUsageDao
import uz.faceguard.app.data.db.DailyAppUsageEntity
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageDateKey

/**
 * Room-backed screen-time usage accounting.
 *
 * Writes go through the DAO's atomic increment (never a read-modify-write), so
 * parallel reports for the same bucket accumulate exactly. The entity <-> domain
 * mapping stays here, so the domain layer never sees Room types.
 *
 * Malformed input is a programming error in the accounting layer (the engine owns
 * the values), so it fails fast with [IllegalArgumentException] rather than filing
 * usage under a wrong or unusable key — the same style as `PolicySettingsRepository`.
 */
@Singleton
class ScreenTimeUsageRepositoryImpl @Inject constructor(
    private val dao: DailyAppUsageDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScreenTimeUsageRepository {

    override suspend fun addUsage(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String,
        category: AppCategory,
        deltaMs: Long,
    ) {
        val pkg = requireScope(accountId, childId, dateKey, packageName)
        require(deltaMs >= 0L) { "deltaMs must not be negative, was $deltaMs" }
        require(deltaMs <= ScreenTimeUsageRepository.MAX_DELTA_MS) {
            "deltaMs must not exceed one day (${ScreenTimeUsageRepository.MAX_DELTA_MS}), was $deltaMs"
        }
        // A zero delta reports "no time passed": creating a 0ms row would add noise
        // without changing any total, so it is deliberately a no-op.
        if (deltaMs == 0L) return

        val now = clock()
        dao.insertIfAbsent(
            DailyAppUsageEntity(
                accountId = accountId,
                childId = childId,
                dateKey = dateKey,
                packageName = pkg,
                usedMs = 0L,
                category = category.name,
                updatedAt = now,
            ),
        )
        dao.incrementUsage(accountId, childId, dateKey, pkg, deltaMs, now)
    }

    override suspend fun getAppUsageMs(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String,
    ): Long {
        val pkg = requireScope(accountId, childId, dateKey, packageName)
        return dao.packageUsage(accountId, childId, dateKey, pkg)?.usedMs ?: 0L
    }

    override suspend fun getCategoryUsageMs(
        accountId: Long,
        childId: Long,
        dateKey: String,
        category: AppCategory,
    ): Long {
        requireScope(accountId, childId, dateKey)
        return dao.categoryUsedMs(accountId, childId, dateKey, category.name) ?: 0L
    }

    override suspend fun getTotalUsageMs(accountId: Long, childId: Long, dateKey: String): Long {
        requireScope(accountId, childId, dateKey)
        return dao.totalUsedMs(accountId, childId, dateKey) ?: 0L
    }

    override suspend fun getDayUsage(
        accountId: Long,
        childId: Long,
        dateKey: String,
    ): List<AppUsage> {
        requireScope(accountId, childId, dateKey)
        return dao.dayUsage(accountId, childId, dateKey).map { it.toDomainUsage() }
    }

    override fun observeDayUsage(
        accountId: Long,
        childId: Long,
        dateKey: String,
    ): Flow<List<AppUsage>> {
        requireScope(accountId, childId, dateKey)
        return dao.observeDay(accountId, childId, dateKey).map { rows -> rows.map { it.toDomainUsage() } }
    }

    /**
     * Rejects the inputs that must never reach the database and returns the
     * normalized package name (the same value both writes and reads then use, so a
     * write and its query can never key on different strings).
     */
    private fun requireScope(
        accountId: Long,
        childId: Long,
        dateKey: String,
        packageName: String? = null,
    ): String {
        require(accountId > 0L) { "accountId must be positive, was $accountId" }
        require(childId > 0L) { "childId must be positive, was $childId" }
        require(UsageDateKey.isValid(dateKey)) { "dateKey must be canonical yyyy-MM-dd, was '$dateKey'" }
        val pkg = packageName?.trim()
        if (pkg != null) require(pkg.isNotEmpty()) { "packageName must not be blank" }
        return pkg.orEmpty()
    }
}
