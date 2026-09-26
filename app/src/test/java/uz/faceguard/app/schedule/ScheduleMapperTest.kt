package uz.faceguard.app.schedule

import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.data.db.ScheduleRuleEntity
import uz.faceguard.app.data.repository.toDomainSchedule
import uz.faceguard.app.data.repository.toEntity
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleDraft
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow

/**
 * Phase 5 Step 2 (pure JVM): the Room entity <-> domain round trip.
 *
 * Runs without a database, so the normal unit-test task verifies it. Every field the
 * persistence layer carries is asserted to survive unchanged, including the cross-midnight
 * window and the day mask, and corrupt stored rows are asserted to fail loudly rather than
 * being silently reinterpreted.
 */
class ScheduleMapperTest {

    private val mondayWednesdayFriday = ScheduleDays.of(
        DayOfWeek.MONDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.FRIDAY,
    )

    private fun entity(
        id: Long = 42L,
        accountId: Long = 1L,
        childId: Long = 10L,
        name: String = "Bedtime",
        mode: String = ScheduleMode.SLEEP.name,
        enabled: Boolean = true,
        startMinuteOfDay: Int = 1320,
        endMinuteOfDay: Int = 420,
        daysMask: Int = mondayWednesdayFriday.mask,
        priority: Int = 7,
        action: String = ProtectionAction.HARD_BLOCK.name,
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
    ) = ScheduleRuleEntity(
        id = id,
        accountId = accountId,
        childId = childId,
        name = name,
        mode = mode,
        enabled = enabled,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        daysMask = daysMask,
        priority = priority,
        action = action,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ---- entity -> domain ---------------------------------------------------

    @Test
    fun `entity maps to the domain rule unchanged`() {
        val rule = entity().toDomainSchedule()

        assertEquals(42L, rule.id)
        assertEquals("Bedtime", rule.name)
        assertEquals(ScheduleMode.SLEEP, rule.mode)
        assertEquals(ProtectionAction.HARD_BLOCK, rule.action)
        assertEquals(7, rule.priority)
        assertTrue(rule.enabled)
        assertEquals(mondayWednesdayFriday, rule.days)
        assertEquals(LocalTime.of(22, 0), rule.window.start)
        assertEquals(LocalTime.of(7, 0), rule.window.end)
        assertTrue("22:00 -> 07:00 must still cross midnight", rule.window.crossesMidnight)
        assertEquals(1320, rule.window.startMinuteOfDay)
        assertEquals(420, rule.window.endMinuteOfDay)
    }

    @Test
    fun `cross midnight window survives the round trip`() {
        val rule = entity(startMinuteOfDay = 1320, endMinuteOfDay = 420).toDomainSchedule()

        val restored = rule
            .toEntity(accountId = 1L, childId = 10L, createdAt = 1_000L, updatedAt = 2_000L)
            .toDomainSchedule()

        assertEquals(1320, restored.window.startMinuteOfDay)
        assertEquals(420, restored.window.endMinuteOfDay)
        assertTrue(restored.window.crossesMidnight)
        assertFalse("a cross-midnight window is not a same-day interval", restored.window.containsMinute(600))
    }

    @Test
    fun `disabled and false flags survive`() {
        val rule = entity(enabled = false).toDomainSchedule()

        assertFalse(rule.enabled)
    }

    @Test
    fun `unknown mode fails loudly instead of silently changing meaning`() {
        assertThrows(IllegalArgumentException::class.java) {
            entity(mode = "NOT_A_MODE").toDomainSchedule()
        }
    }

    @Test
    fun `unknown action fails loudly instead of silently changing meaning`() {
        assertThrows(IllegalArgumentException::class.java) {
            entity(action = "NOT_AN_ACTION").toDomainSchedule()
        }
    }

    @Test
    fun `a domain-only action that is not implemented fails loudly`() {
        // DIM exists in the enum but is not in IMPLEMENTED_ACTIONS, so it is not a valid
        // schedule action and must not be silently accepted.
        assertThrows(IllegalArgumentException::class.java) {
            entity(action = ProtectionAction.DIM.name).toDomainSchedule()
        }
    }

    @Test
    fun `an empty day mask fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            entity(daysMask = 0).toDomainSchedule()
        }
    }

    @Test
    fun `an out of range window fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            entity(startMinuteOfDay = 1440, endMinuteOfDay = 420).toDomainSchedule()
        }
    }

    @Test
    fun `a zero length stored window fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            entity(startMinuteOfDay = 600, endMinuteOfDay = 600).toDomainSchedule()
        }
    }

    // ---- draft -> entity ----------------------------------------------------

    @Test
    fun `draft maps to an entity with the fields the domain owns`() {
        val draft = ScheduleDraft(
            name = "Study",
            mode = ScheduleMode.STUDY,
            window = ScheduleWindow.ofMinutes(960, 1_080),
            days = mondayWednesdayFriday,
            action = ProtectionAction.SOFT_BLOCK,
            priority = 3,
            enabled = false,
        )

        val row = draft.toEntity(accountId = 1L, childId = 10L, id = 0L, createdAt = 1_000L, updatedAt = 1_000L)

        assertEquals("a new row asks Room to generate the id", 0L, row.id)
        assertEquals(1L, row.accountId)
        assertEquals(10L, row.childId)
        assertEquals("Study", row.name)
        assertEquals(ScheduleMode.STUDY.name, row.mode)
        assertEquals(ProtectionAction.SOFT_BLOCK.name, row.action)
        assertEquals(960, row.startMinuteOfDay)
        assertEquals(1_080, row.endMinuteOfDay)
        assertEquals(mondayWednesdayFriday.mask, row.daysMask)
        assertEquals(3, row.priority)
        assertFalse(row.enabled)
        assertEquals(1_000L, row.createdAt)
        assertEquals(1_000L, row.updatedAt)
    }

    // ---- rule -> entity -----------------------------------------------------

    @Test
    fun `rule maps to an entity preserving the id and createdAt`() {
        val rule = entity().toDomainSchedule()

        val row = rule.toEntity(accountId = 1L, childId = 10L, createdAt = 1_000L, updatedAt = 5_000L)

        assertEquals(rule.id, row.id)
        assertEquals(1_000L, row.createdAt)
        assertEquals(5_000L, row.updatedAt)
        assertEquals(rule.window.startMinuteOfDay, row.startMinuteOfDay)
        assertEquals(rule.window.endMinuteOfDay, row.endMinuteOfDay)
        assertEquals(rule.days.mask, row.daysMask)
    }

    // ---- full round trip ----------------------------------------------------

    @Test
    fun `domain survives entity round trip unchanged`() {
        val original = ScheduleRule(
            id = 99L,
            name = "School night",
            mode = ScheduleMode.SCHOOL,
            window = ScheduleWindow.ofMinutes(1_320, 420),
            days = ScheduleDays.WEEKDAYS,
            action = ProtectionAction.MUTE,
            priority = -2,
            enabled = true,
        )

        val restored = original
            .toEntity(accountId = 4L, childId = 12L, createdAt = 10L, updatedAt = 20L)
            .toDomainSchedule()

        assertEquals(original, restored)
    }

    @Test
    fun `every implemented action survives the round trip`() {
        listOf(
            ProtectionAction.ALLOW,
            ProtectionAction.WARNING,
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
            ProtectionAction.MUTE,
        ).forEach { action ->
            val original = ScheduleRule(
                id = 1L,
                name = "Rule",
                mode = ScheduleMode.CUSTOM,
                window = ScheduleWindow.ofMinutes(0, 60),
                days = ScheduleDays.ALL,
                action = action,
            )

            val restored = original
                .toEntity(accountId = 1L, childId = 1L, createdAt = 0L, updatedAt = 0L)
                .toDomainSchedule()

            assertEquals(action, restored.action)
        }
    }
}
