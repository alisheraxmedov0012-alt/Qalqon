package uz.faceguard.app.domain.screentime

import java.time.Instant
import java.time.ZoneId

/**
 * Phase 4 Step 1B-3: pure usage accounting.
 *
 * Answers "how much time was used?" from reported spans, and turns them into
 * per-day [UsageDelta]s. It never measures anything itself (no clock), never persists
 * (the repository does), and never enforces (a later phase).
 *
 * The two rules that keep the numbers honest:
 *  - spans are unioned *per app* before being counted, so a span reported twice — or
 *    two overlapping spans from one app — is counted once (see [UsageIntervals]);
 *  - a span crossing local midnight is split at the boundary, so a minute that straddles
 *    midnight is filed as two half-minutes on the two days it actually belongs to.
 *
 * The timezone is always an explicit parameter. There is no `systemDefault()` here, so
 * the same input always yields the same output and behaviour is testable.
 */
object UsageAccounting {

    /**
     * Converts reported [intervals] into the minimal set of per-day, per-app deltas.
     *
     * Spans are grouped by app, unioned, split at midnight and summed. Two identical
     * intervals produce exactly the same deltas as one, which is the schema-free basis
     * for not double counting a re-reported span.
     *
     * Output order is deterministic (by day, then app), so results can be compared
     * directly in tests.
     */
    fun deltasFor(intervals: List<UsageInterval>, zone: ZoneId): List<UsageDelta> {
        if (intervals.isEmpty()) return emptyList()

        // Normalize the package name once, so a write and its later read agree, and
        // resolve the category once per app so every delta for it agrees too.
        val byPackage = intervals.groupBy { it.packageName.trim() }
        val categoryByPackage = byPackage.mapValues { (_, app) -> categoryOf(app.map { it.category }) }
        val elapsedByDayAndPackage = LinkedHashMap<Pair<String, String>, Long>()

        for (packageName in byPackage.keys.sorted()) {
            val category = categoryByPackage.getValue(packageName)
            val ranges = UsageIntervals.merge(byPackage.getValue(packageName).map { it.range })
            for (range in ranges) {
                val interval = UsageInterval(packageName, category, range.startTimeMs, range.endTimeMs)
                for (delta in splitAtMidnight(interval, zone)) {
                    val key = delta.dateKey to delta.packageName
                    elapsedByDayAndPackage[key] = (elapsedByDayAndPackage[key] ?: 0L) + delta.elapsedMs
                }
            }
        }

        return elapsedByDayAndPackage.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .flatMap { (key, elapsedMs) ->
                val (dateKey, packageName) = key
                val category = categoryByPackage.getValue(packageName)
                // One delta per day and app is the normal case. A local day can be
                // longer than 24h when the clock shifts (DST fall-back), so a day's
                // total can exceed the repository's one-day bound; splitting into
                // whole-day deltas keeps that lossless instead of failing the batch.
                chunksOfOneDay(elapsedMs).map { UsageDelta(dateKey, packageName, category, it) }
            }
    }

    private fun chunksOfOneDay(totalMs: Long): List<Long> {
        if (totalMs <= ScreenTimeUsageRepository.MAX_DELTA_MS) return listOf(totalMs)
        val chunks = ArrayList<Long>(2)
        var remaining = totalMs
        while (remaining > ScreenTimeUsageRepository.MAX_DELTA_MS) {
            chunks += ScreenTimeUsageRepository.MAX_DELTA_MS
            remaining -= ScreenTimeUsageRepository.MAX_DELTA_MS
        }
        chunks += remaining
        return chunks
    }

    /**
     * Splits [interval] at every local midnight it spans, in [zone].
     *
     * `atStartOfDay(zone)` is used rather than a fixed 24h offset, so a day on which the
     * local clock shifts (DST) still splits exactly at midnight. Each returned delta is
     * within one day, because the original interval is.
     */
    fun splitAtMidnight(interval: UsageInterval, zone: ZoneId): List<UsageDelta> {
        val deltas = ArrayList<UsageDelta>(2)
        var cursor = interval.startTimeMs

        while (cursor < interval.endTimeMs) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(interval.endTimeMs, nextMidnight)
            check(segmentEnd > cursor) { "midnight split made no progress at $cursor in $zone" }

            deltas += UsageDelta(
                dateKey = day.toString(),
                packageName = interval.packageName.trim(),
                category = interval.category,
                elapsedMs = segmentEnd - cursor,
            )
            cursor = segmentEnd
        }
        return deltas
    }

    /**
     * Aggregates [deltas] of one [dateKey] into the existing [ChildDayUsage] shape
     * (app totals, category totals, grand total), sorted by app.
     *
     * Only deltas of that day are counted, so two days can never be mixed. A delta for
     * another day is ignored rather than misattributed.
     */
    fun summarize(childId: Long, dateKey: String, deltas: List<UsageDelta>): ChildDayUsage {
        val apps = deltas
            .filter { it.dateKey == dateKey }
            .groupBy { it.packageName }
            .entries
            .sortedBy { it.key }
            .map { (packageName, packageDeltas) ->
                AppUsage(
                    packageName = packageName,
                    category = categoryOf(packageDeltas.map { it.category }),
                    usedMs = packageDeltas.sumOf { it.elapsedMs },
                )
            }
        return ChildDayUsage(childId = childId, dateKey = dateKey, apps = apps)
    }

    /**
     * One app must not be filed under two categories in the same day. If a caller
     * reports conflicting categories, the first declared [AppCategory] wins, so the
     * choice is stable and does not depend on input order.
     */
    private fun categoryOf(categories: Collection<AppCategory>): AppCategory =
        categories.minByOrNull { it.ordinal } ?: AppCategory.OTHER
}
