package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.faceguard.app.data.db.DailyAppUsageEntity
import uz.faceguard.app.data.repository.toDomainUsage
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.AppUsage

/**
 * Phase 4 Step 1B-2 (pure JVM): the Room entity -> domain mapping used by
 * [uz.faceguard.app.data.repository.ScreenTimeUsageRepositoryImpl].
 *
 * The stored category is authoritative, so no re-classification happens here; an
 * unusable name must degrade to [AppCategory.OTHER] rather than making the whole day
 * unreadable.
 */
class ScreenTimeUsageMapperTest {

    private fun row(category: String) = DailyAppUsageEntity(
        accountId = 1L,
        childId = 10L,
        dateKey = "2026-09-24",
        packageName = "com.google.android.youtube",
        usedMs = 1_000L,
        category = category,
        updatedAt = 7L,
    )

    @Test
    fun mapsTheStoredCategoryAndMillisecondsUnchanged() {
        assertEquals(
            AppUsage("com.google.android.youtube", AppCategory.EDUCATION, 1_000L),
            row("EDUCATION").toDomainUsage(),
        )
    }

    @Test
    fun anUnknownCategoryFallsBackToOtherInsteadOfThrowing() {
        assertEquals(AppCategory.OTHER, row("NOT_A_REAL_CATEGORY").toDomainUsage().category)
        assertEquals(AppCategory.OTHER, row("").toDomainUsage().category)
    }

    @Test
    fun millisecondsAreNeverRounded() {
        val odd = row("VIDEO").copy(usedMs = 59_999L)
        assertEquals(59_999L, odd.toDomainUsage().usedMs)
    }
}
