package uz.faceguard.app.data.repository

import uz.faceguard.app.data.db.UsageSnapshotCheckpointEntity
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpoint
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-6: Room entity <-> domain checkpoint mapping, kept out of the repository
 * so it is unit-testable on the JVM (no database needed).
 *
 * [toDomainCheckpoint] returns `null` for a row that cannot describe a real baseline — an
 * unknown source, a negative counter or timestamp, or an impossible window. Treating such a
 * row as *absent* is the fail-safe direction: an absent checkpoint makes the next
 * observation a fresh baseline, which accounts for nothing, whereas trusting a corrupt row
 * could attribute invented usage to a child.
 */
internal fun UsageSnapshotCheckpointEntity.toDomainCheckpoint(): UsageSnapshotCheckpoint? {
    val source = runCatching { UsageSourceId.valueOf(source) }.getOrNull() ?: return null
    if (accountId <= 0L || childId <= 0L) return null
    if (packageName.isBlank()) return null
    if (cumulativeForegroundMs < 0L || observedAtMs < 0L) return null
    if (windowStartMs < 0L || windowEndMs <= windowStartMs) return null

    return UsageSnapshotCheckpoint(
        accountId = accountId,
        childId = childId,
        source = source,
        range = UsageRange(windowStartMs, windowEndMs),
        packageName = packageName,
        cumulativeForegroundMs = cumulativeForegroundMs,
        observedAtMs = observedAtMs,
    )
}

internal fun UsageSnapshotCheckpoint.toEntity(): UsageSnapshotCheckpointEntity =
    UsageSnapshotCheckpointEntity(
        accountId = accountId,
        childId = childId,
        source = source.name,
        windowStartMs = range.startTimeMs,
        windowEndMs = range.endTimeMs,
        // Trimmed here so a save and its later read key on exactly the same package name
        // (the usage repository normalizes the same way).
        packageName = packageName.trim(),
        cumulativeForegroundMs = cumulativeForegroundMs,
        observedAtMs = observedAtMs,
    )
