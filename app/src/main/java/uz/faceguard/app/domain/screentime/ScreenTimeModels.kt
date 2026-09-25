package uz.faceguard.app.domain.screentime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 4: the Screen Time domain (pure, no Android, no I/O).
 *
 * Durations are always `Long` milliseconds internally (minutes are a UI concern),
 * so a "29m 59s / 30m" boundary is exact and never truncated away.
 * A limit is `null` = unlimited, `0` = immediately exceeded, `1..1440` = daily quota.
 */
object ScreenTimeLimits {
    const val MINUTES_PER_DAY = 24 * 60
    const val MS_PER_MINUTE = 60_000L

    /** Negative limits are invalid; anything above a day is clamped to the maximum. */
    fun normalize(limitMinutes: Int?): Int? = when {
        limitMinutes == null -> null
        limitMinutes < 0 -> null
        limitMinutes > MINUTES_PER_DAY -> MINUTES_PER_DAY
        else -> limitMinutes
    }

    fun isAccepted(limitMinutes: Int?): Boolean = limitMinutes == null || limitMinutes in 0..MINUTES_PER_DAY

    fun toMillis(limitMinutes: Int): Long = limitMinutes.toLong() * MS_PER_MINUTE
}

enum class LimitState { UNLIMITED, WITHIN_LIMIT, EXCEEDED }

/** Limit + usage + derived state for one scope. */
data class LimitStatus(
    val limitMinutes: Int?,
    val usedMs: Long,
    val state: LimitState,
) {
    val unlimited: Boolean get() = state == LimitState.UNLIMITED
    val exceeded: Boolean get() = state == LimitState.EXCEEDED

    /** Null when unlimited; otherwise never negative. */
    val remainingMs: Long?
        get() = limitMinutes?.let { (ScreenTimeLimits.toMillis(it) - usedMs).coerceAtLeast(0L) }
}

/** Pure: the single place the limit arithmetic lives. */
fun limitStatus(limitMinutes: Int?, usedMs: Long): LimitStatus {
    val normalized = ScreenTimeLimits.normalize(limitMinutes)
    val used = usedMs.coerceAtLeast(0L)
    if (normalized == null) return LimitStatus(null, used, LimitState.UNLIMITED)
    val limitMs = ScreenTimeLimits.toMillis(normalized)
    return LimitStatus(normalized, used, if (used >= limitMs) LimitState.EXCEEDED else LimitState.WITHIN_LIMIT)
}

/** Which scope a limit was configured for. */
enum class LimitScope { TOTAL, APP, CATEGORY }

/** The scope that currently triggers enforcement (strictest first). */
enum class ScreenTimeLimitScope { NONE, APP, CATEGORY, TOTAL }

/**
 * Minimal, deterministic package -> category mapping.
 *
 * Deliberately small: a category limit only needs a deterministic grouping, not an
 * app catalogue. Unknown packages fall back to [AppCategory.OTHER].
 */
enum class AppCategory { VIDEO, GAMES, EDUCATION, SOCIAL, OTHER }

object AppCategories {
    private val OVERRIDES = mapOf(
        "com.google.android.youtube" to AppCategory.VIDEO,
        "com.google.android.apps.youtube.music" to AppCategory.VIDEO,
        "com.netflix.mediaclient" to AppCategory.VIDEO,
        "com.google.android.youtube.kids" to AppCategory.VIDEO,
        "org.telegram.messenger" to AppCategory.SOCIAL,
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.zhiliaoapp.musically" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.whatsapp" to AppCategory.SOCIAL,
        "com.duolingo" to AppCategory.EDUCATION,
        "org.khanacademy.android" to AppCategory.EDUCATION,
    )

    private val GAME_HINTS = listOf("game", "games", "oyun", "supercell", "gameloft", "king.", "roblox")
    private val VIDEO_HINTS = listOf("video", "player", "movie", "tv")
    private val EDUCATION_HINTS = listOf("edu", "learn", "school", "study", "kids", "academy")

    fun categoryFor(packageName: String): AppCategory {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return AppCategory.OTHER
        OVERRIDES[pkg]?.let { return it }
        return when {
            GAME_HINTS.any { pkg.contains(it) } -> AppCategory.GAMES
            EDUCATION_HINTS.any { pkg.contains(it) } -> AppCategory.EDUCATION
            VIDEO_HINTS.any { pkg.contains(it) } -> AppCategory.VIDEO
            else -> AppCategory.OTHER
        }
    }
}

/** Local calendar day key (`yyyy-MM-dd`) in the device timezone. */
object UsageDateKey {
    fun of(wallClockMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(wallClockMillis).atZone(zone).toLocalDate().toString()

    fun zoneOf(wallClockMillis: Long): ZoneId = ZoneId.systemDefault()

    /**
     * True when [dateKey] is exactly the canonical `yyyy-MM-dd` form [of] produces
     * (so `2026-1-1` and `01.01.2026` are rejected). Callers supply the key; this
     * keeps every accepted key fileable under the same day as [of] would produce.
     */
    fun isValid(dateKey: String): Boolean =
        runCatching { LocalDate.parse(dateKey).toString() == dateKey }.getOrDefault(false)

    /**
     * The half-open local-day window `[00:00, next 00:00)` containing [wallClockMillis].
     *
     * Phase 4 Step 1B-7: the collection window. Built with `atStartOfDay(zone)` rather
     * than a fixed 24h offset, so a day on which the local clock shifts still starts and
     * ends exactly at midnight, and it is always a *single* day — which is what the
     * accounting contract requires, because a snapshot total cannot be split across days.
     * The zone is the device's by default (product-local semantics) and is never a
     * hard-coded UTC or region.
     */
    fun dayRange(wallClockMillis: Long, zone: ZoneId = ZoneId.systemDefault()): UsageRange {
        val day = Instant.ofEpochMilli(wallClockMillis).atZone(zone).toLocalDate()
        return UsageRange(
            startTimeMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
            endTimeMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}

/** Usage of one app on one day. */
data class AppUsage(val packageName: String, val category: AppCategory, val usedMs: Long)

/**
 * One child's usage for one day, built from *controlled* apps only (the global
 * protected catalogue and/or an explicit per-child policy). Apps QALQON does not
 * control never count towards the child's total screen time.
 */
data class ChildDayUsage(
    val childId: Long,
    val dateKey: String,
    val apps: List<AppUsage> = emptyList(),
) {
    val totalMs: Long get() = apps.sumOf { it.usedMs }

    fun usedMsFor(packageName: String): Long =
        apps.firstOrNull { it.packageName == packageName }?.usedMs ?: 0L

    fun usedMsFor(category: AppCategory): Long =
        apps.filter { it.category == category }.sumOf { it.usedMs }
}

/** Everything the policy layer needs to know about one child's day. */
data class ScreenTimeSnapshot(
    val childId: Long,
    val dateKey: String,
    val usage: ChildDayUsage,
    val totalLimitMinutes: Int? = null,
    val categoryLimits: Map<AppCategory, Int?> = emptyMap(),
    val appLimits: Map<String, Int?> = emptyMap(),
) {
    fun totalStatus(): LimitStatus = limitStatus(totalLimitMinutes, usage.totalMs)

    fun appStatus(packageName: String): LimitStatus {
        val limit = appLimits[packageName] ?: return LimitStatus(null, usage.usedMsFor(packageName), LimitState.UNLIMITED)
        return limitStatus(limit, usage.usedMsFor(packageName))
    }

    fun categoryStatus(category: AppCategory): LimitStatus {
        val limit = categoryLimits[category] ?: return LimitStatus(null, usage.usedMsFor(category), LimitState.UNLIMITED)
        return limitStatus(limit, usage.usedMsFor(category))
    }

    /**
     * Which scope (if any) blocks [packageName] right now.
     *
     * Precedence is deterministic and strictest-first: an app-specific limit wins
     * over its category limit, which wins over the device-wide total. An explicit
     * app ALLOW is expressed by the snapshot not carrying an app limit for it; the
     * *total* cap still applies to every controlled app (that is what a total cap
     * means), which the policy layer documents and tests.
     */
    fun blockingScope(packageName: String): ScreenTimeLimitScope = when {
        appStatus(packageName).exceeded -> ScreenTimeLimitScope.APP
        categoryStatus(AppCategories.categoryFor(packageName)).exceeded -> ScreenTimeLimitScope.CATEGORY
        totalStatus().exceeded -> ScreenTimeLimitScope.TOTAL
        else -> ScreenTimeLimitScope.NONE
    }
}
