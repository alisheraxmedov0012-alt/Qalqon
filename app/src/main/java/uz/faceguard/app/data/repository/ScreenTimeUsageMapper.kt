package uz.faceguard.app.data.repository

import uz.faceguard.app.data.db.DailyAppUsageEntity
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage

/**
 * Room entity -> domain mapping, kept out of the repository class so it is
 * unit-testable on the JVM (no database needed).
 *
 * The row's category was resolved when it was written, so it is authoritative
 * and never re-classified here. An unknown/corrupted name falls back to
 * [AppCategory.OTHER] instead of throwing, so one bad row cannot make a whole
 * day unreadable.
 */
internal fun DailyAppUsageEntity.toDomainUsage(): AppUsage = AppUsage(
    packageName = packageName,
    category = runCatching { AppCategory.valueOf(category) }.getOrDefault(AppCategory.OTHER),
    usedMs = usedMs,
)
