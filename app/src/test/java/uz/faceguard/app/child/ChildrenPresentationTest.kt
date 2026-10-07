package uz.faceguard.app.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.feature.child.ChildCardStatus
import uz.faceguard.app.feature.child.ChildScreenTime
import uz.faceguard.app.feature.child.childOverviews

/**
 * UI/UX redesign Phase 4: the Children list's presentation mapping.
 *
 * Pure JVM — the list states (empty/one/many, enrolled/not) are pinned without a
 * device.
 */
class ChildrenPresentationTest {

    @Test
    fun anEmptyAccountProducesNoRows() {
        assertTrue(childOverviews(emptyList()).isEmpty())
    }

    @Test
    fun oneChildProducesOneRowWithItsRealProfileData() {
        val rows = childOverviews(listOf(child(1, "Ali", RestrictionLevel.HIGH, enrolled = true)))
        assertEquals(1, rows.size)
        with(rows.single()) {
            assertEquals(1L, childId)
            assertEquals("Ali", name)
            assertEquals("A", initial)
            assertTrue(faceEnrolled)
            assertEquals(RestrictionLevel.HIGH, level)
            assertFalse(needsSetup)
        }
    }

    @Test
    fun multipleChildrenKeepTheirOrder() {
        val rows = childOverviews(
            listOf(child(1, "Ali"), child(2, "Vali"), child(3, "Hasan")),
        )
        assertEquals(listOf("Ali", "Vali", "Hasan"), rows.map { it.name })
    }

    @Test
    fun aChildWithoutAFaceNeedsSetup() {
        val row = childOverviews(listOf(child(1, "Ali", enrolled = false))).single()
        assertFalse(row.faceEnrolled)
        assertTrue(row.needsSetup)
    }

    @Test
    fun aBlankNameStillYieldsAnAvatarInitial() {
        val row = childOverviews(listOf(child(1, "   "))).single()
        assertEquals("?", row.initial)
    }

    @Test
    fun theInitialIsUppercasedAndSingleCharacter() {
        val row = childOverviews(listOf(child(1, "ali"))).single()
        assertEquals("A", row.initial)
    }

    // ------------------------------------------------------- status + screen time

    @Test
    fun anEnrolledChildWithinLimitIsActive() {
        val row = childOverviews(
            listOf(child(1, "Ali", enrolled = true)),
            mapOf(1L to ChildScreenTime(usedMs = 30_000L, limitMinutes = 60, exceeded = false)),
        ).single()
        assertEquals(ChildCardStatus.ACTIVE, row.status)
    }

    @Test
    fun anEnrolledChildOverTheLimitIsOutOfTime() {
        val row = childOverviews(
            listOf(child(1, "Ali", enrolled = true)),
            mapOf(1L to ChildScreenTime(usedMs = 3_600_000L, limitMinutes = 60, exceeded = true)),
        ).single()
        assertEquals(ChildCardStatus.TIME_UP, row.status)
    }

    @Test
    fun aChildWithoutAFaceIsOffline() {
        val row = childOverviews(listOf(child(1, "Ali", enrolled = false))).single()
        assertEquals(ChildCardStatus.OFFLINE, row.status)
    }

    @Test
    fun aReachedLimitOutranksAMissingFace() {
        val row = childOverviews(
            listOf(child(1, "Ali", enrolled = false)),
            mapOf(1L to ChildScreenTime(usedMs = 60_000L, limitMinutes = 0, exceeded = true)),
        ).single()
        assertEquals(ChildCardStatus.TIME_UP, row.status)
    }

    @Test
    fun aChildAbsentFromTheScreenTimeMapHasNoScreenTime() {
        val row = childOverviews(listOf(child(1, "Ali")), emptyMap()).single()
        assertNull(row.screenTime)
    }

    @Test
    fun screenTimeProgressNeedsALimit() {
        val unlimited = ChildScreenTime(usedMs = 1_000L, limitMinutes = null, exceeded = false)
        assertFalse(unlimited.hasLimit)
        assertNull(unlimited.progress)
    }

    @Test
    fun screenTimeProgressIsClampedBetweenZeroAndOne() {
        assertEquals(0.5f, ChildScreenTime(30L * 60_000L, 60, false).progress!!, 0.0001f)
        assertEquals(1f, ChildScreenTime(90L * 60_000L, 60, true).progress!!, 0.0001f)
        assertEquals(0f, ChildScreenTime(0L, 60, false).progress!!, 0.0001f)
    }

    @Test
    fun aZeroMinuteLimitIsImmediatelyFull() {
        assertEquals(1f, ChildScreenTime(usedMs = 0L, limitMinutes = 0, exceeded = true).progress!!, 0.0001f)
    }

    @Test
    fun anInvalidLimitHasNoProgress() {
        val invalid = ChildScreenTime(
            usedMs = 1_000L,
            limitMinutes = null,
            exceeded = false,
            invalidLimit = true,
        )
        assertFalse(invalid.hasLimit)
        assertNull(invalid.progress)
    }

    private fun child(
        id: Long,
        name: String = "Child $id",
        level: RestrictionLevel = RestrictionLevel.MEDIUM,
        enrolled: Boolean = true,
    ) = ChildProfile(
        id = id,
        accountId = 1L,
        childName = name,
        isFaceEnrolled = enrolled,
        restrictionLevel = level,
    )
}
