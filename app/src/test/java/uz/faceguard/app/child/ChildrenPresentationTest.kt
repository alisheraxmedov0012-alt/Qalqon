package uz.faceguard.app.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
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
