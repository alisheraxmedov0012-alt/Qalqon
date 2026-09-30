package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.data.db.UsageSnapshotCheckpointEntity
import uz.faceguard.app.data.repository.toDomainCheckpoint
import uz.faceguard.app.data.repository.toEntity
import uz.faceguard.app.domain.screentime.UsageRange
import uz.faceguard.app.domain.screentime.UsageSnapshotCheckpoint
import uz.faceguard.app.domain.screentime.UsageSourceId

/**
 * Phase 4 Step 1B-6 (pure JVM): the checkpoint model's validation, its identity, and the
 * entity <-> domain mapping — all without a database.
 */
class UsageSnapshotCheckpointTest {

    private val youtube = "com.google.android.youtube"

    /** The whole of 2026-09-25 UTC. */
    private fun window(start: Long = 1_793_001_600_000L, end: Long = 1_793_088_000_000L) =
        UsageRange(start, end)

    private fun checkpoint(
        accountId: Long = 1L,
        childId: Long = 10L,
        source: UsageSourceId = UsageSourceId.USAGE_STATS,
        range: UsageRange = window(),
        packageName: String = youtube,
        cumulativeForegroundMs: Long = 10_000L,
        observedAtMs: Long = 1_793_001_700_000L,
    ) = UsageSnapshotCheckpoint(
        accountId = accountId,
        childId = childId,
        source = source,
        range = range,
        packageName = packageName,
        cumulativeForegroundMs = cumulativeForegroundMs,
        observedAtMs = observedAtMs,
    )

    // ---- validation ---------------------------------------------------------

    @Test
    fun negativeCumulativeRejected() {
        assertThrows(IllegalArgumentException::class.java) { checkpoint(cumulativeForegroundMs = -1L) }
    }

    @Test
    fun zeroCumulativeIsAValidObservation() {
        assertEquals(0L, checkpoint(cumulativeForegroundMs = 0L).cumulativeForegroundMs)
    }

    @Test
    fun negativeObservedAtRejected() {
        assertThrows(IllegalArgumentException::class.java) { checkpoint(observedAtMs = -1L) }
    }

    @Test
    fun invalidRangeRejected() {
        assertThrows(IllegalArgumentException::class.java) { checkpoint(range = UsageRange(1_000L, 1_000L)) }
        assertThrows(IllegalArgumentException::class.java) { checkpoint(range = UsageRange(2_000L, 1_000L)) }
        assertThrows(IllegalArgumentException::class.java) { checkpoint(range = UsageRange(-1L, 1_000L)) }
    }

    @Test
    fun identityRequiresPositiveAccountAndChild() {
        assertThrows(IllegalArgumentException::class.java) { checkpoint(accountId = 0L) }
        assertThrows(IllegalArgumentException::class.java) { checkpoint(childId = -1L) }
    }

    @Test
    fun blankPackageRejected() {
        assertThrows(IllegalArgumentException::class.java) { checkpoint(packageName = "  ") }
    }

    // ---- identity -----------------------------------------------------------

    @Test
    fun theSameContextIsTheSameIdentity() {
        assertTrue(checkpoint().hasSameIdentityAs(checkpoint()))
    }

    @Test
    fun onlyACompleteKeyMatchIsTheSameIdentity() {
        assertFalse("different account", checkpoint().hasSameIdentityAs(checkpoint(accountId = 2L)))
        assertFalse("different child", checkpoint().hasSameIdentityAs(checkpoint(childId = 11L)))
        assertFalse(
            "different window",
            checkpoint().hasSameIdentityAs(checkpoint(range = window(1_793_088_000_000L, 1_793_174_400_000L))),
        )
        assertFalse("different package", checkpoint().hasSameIdentityAs(checkpoint(packageName = "com.a")))
    }

    @Test
    fun theIdentityIgnoresTheObservedValueSoAnUpdateKeepsItsRow() {
        val first = checkpoint(cumulativeForegroundMs = 10_000L)
        val second = checkpoint(cumulativeForegroundMs = 15_000L, observedAtMs = 1_793_002_000_000L)

        assertTrue("an update is the same row", first.hasSameIdentityAs(second))
        assertNotEquals(first.cumulativeForegroundMs, second.cumulativeForegroundMs)
    }

    @Test
    fun theSourceIsPartOfTheIdentity() {
        val usageStats = checkpoint(source = UsageSourceId.USAGE_STATS)

        assertEquals(UsageSourceId.USAGE_STATS, usageStats.source)
        assertEquals("only one source exists in this step", 1, UsageSourceId.entries.size)
    }

    // ---- mapping ------------------------------------------------------------

    @Test
    fun entityAndDomainMapWithoutLosingAnything() {
        val original = checkpoint()

        val roundTripped = original.toEntity().toDomainCheckpoint()

        assertEquals(original.accountId, roundTripped!!.accountId)
        assertEquals(original.childId, roundTripped.childId)
        assertEquals(original.source, roundTripped.source)
        assertEquals(original.range, roundTripped.range)
        assertEquals(original.packageName, roundTripped.packageName)
        assertEquals(original.cumulativeForegroundMs, roundTripped.cumulativeForegroundMs)
        assertEquals(original.observedAtMs, roundTripped.observedAtMs)
    }

    @Test
    fun mappingStoresTheWindowBoundsAndTheSourceName() {
        val entity = checkpoint(cumulativeForegroundMs = 12_345L).toEntity()

        assertEquals(1_793_001_600_000L, entity.windowStartMs)
        assertEquals(1_793_088_000_000L, entity.windowEndMs)
        assertEquals("USAGE_STATS", entity.source)
        assertEquals(12_345L, entity.cumulativeForegroundMs)
    }

    @Test
    fun anUnrecognizableSourceIsTreatedAsAbsentRatherThanTrusted() {
        val entity = UsageSnapshotCheckpointEntity(
            accountId = 1L,
            childId = 10L,
            source = "SOME_OTHER_THING",
            windowStartMs = 1_793_001_600_000L,
            windowEndMs = 1_793_088_000_000L,
            packageName = youtube,
            cumulativeForegroundMs = 10_000L,
            observedAtMs = 1_793_001_700_000L,
        )

        assertEquals(
            "an unknown source must not become a baseline (fail-safe: no usage invented)",
            null,
            entity.toDomainCheckpoint(),
        )
    }

    @Test
    fun anImpossibleStoredRowIsTreatedAsAbsent() {
        fun entity(
            cumulative: Long = 10_000L,
            observedAt: Long = 1_000L,
            start: Long = 1_793_001_600_000L,
            end: Long = 1_793_088_000_000L,
        ) = UsageSnapshotCheckpointEntity(
            accountId = 1L,
            childId = 10L,
            source = "USAGE_STATS",
            windowStartMs = start,
            windowEndMs = end,
            packageName = youtube,
            cumulativeForegroundMs = cumulative,
            observedAtMs = observedAt,
        )

        assertEquals(null, entity(cumulative = -1L).toDomainCheckpoint())
        assertEquals(null, entity(observedAt = -1L).toDomainCheckpoint())
        assertEquals(null, entity(end = 1_793_001_600_000L).toDomainCheckpoint())
        assertEquals(null, entity(start = -1L).toDomainCheckpoint())
    }
}
