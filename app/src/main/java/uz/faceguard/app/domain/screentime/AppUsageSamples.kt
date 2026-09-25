package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-4: pure, deterministic cleanup of raw usage samples.
 *
 * A platform query can return several rows for one package (per bucket, or repeated).
 * They must be summed into one figure per package — and different packages must never
 * merge — before anything is accounted for. No Android, no clock, no database, so this
 * is fully testable on the JVM.
 */
object AppUsageSamples {

    /**
     * Collapses [samples] into at most one sample per package.
     *
     * - A zero duration is not a sample: it means "no usage", and reporting it would
     *   create noise (the repository treats a zero delta as a no-op for the same reason).
     * - Longest-first is deliberately not attempted; order never changes the result.
     * - Output is sorted by package name, so the same input always yields the same list
     *   regardless of how the platform ordered its rows.
     * - Durations are summed with [Math.addExact]: a total that would overflow `Long`
     *   fails loudly instead of wrapping to a negative duration. Accounting stays in
     *   whole milliseconds; no floating point is involved anywhere.
     */
    fun aggregate(samples: List<AppUsageSample>): List<AppUsageSample> {
        if (samples.isEmpty()) return emptyList()

        val totalByPackage = HashMap<String, Long>()
        for (sample in samples) {
            if (sample.foregroundMs == 0L) continue
            val packageName = sample.packageName.trim()
            val running = totalByPackage[packageName] ?: 0L
            totalByPackage[packageName] = try {
                Math.addExact(running, sample.foregroundMs)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException(
                    "usage for '$packageName' overflows Long ($running + ${sample.foregroundMs})",
                )
            }
        }

        return totalByPackage.entries
            .sortedBy { it.key }
            .map { (packageName, foregroundMs) -> AppUsageSample(packageName, foregroundMs) }
    }
}
