package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.data.db.ChildAppPolicyEntity
import uz.faceguard.app.data.repository.toDomainPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * JVM test for the Room entity -> domain mapping. Runs without a database, so
 * it is verified by the normal unit-test task.
 */
class ChildAppPolicyMapperTest {

    @Test
    fun `maps a LIMIT row with a daily limit and delays`() {
        val entity = ChildAppPolicyEntity(
            accountId = 1L,
            childId = 7L,
            packageName = "com.example.youtube",
            mode = AppPolicyMode.LIMIT.name,
            action = ProtectionAction.SOFT_BLOCK.name,
            dailyLimitMinutes = 30,
            activationDelayMs = 5_000L,
            recoveryDelayMs = 30_000L,
        )

        val policy = entity.toDomainPolicy()

        assertEquals("com.example.youtube", policy.packageName)
        assertEquals(AppPolicyMode.LIMIT, policy.mode)
        assertEquals(ProtectionAction.SOFT_BLOCK, policy.action)
        assertEquals(30, policy.dailyLimitMinutes)
        assertEquals(5_000L, policy.activationDelayMs)
        assertEquals(30_000L, policy.recoveryDelayMs)
        assertEquals(7L, policy.childId)
    }

    @Test
    fun `maps a BLOCK row without a limit`() {
        val entity = ChildAppPolicyEntity(
            accountId = 2L,
            childId = 3L,
            packageName = "com.example.game",
            mode = AppPolicyMode.BLOCK.name,
            action = ProtectionAction.HARD_BLOCK.name,
        )

        val policy = entity.toDomainPolicy()

        assertEquals(AppPolicyMode.BLOCK, policy.mode)
        assertEquals(ProtectionAction.HARD_BLOCK, policy.action)
        assertNull(policy.dailyLimitMinutes)
        assertEquals(3L, policy.childId)
    }

    @Test
    fun `unknown enum names fall back to the safest behaviour`() {
        val entity = ChildAppPolicyEntity(
            accountId = 1L,
            childId = 1L,
            packageName = "com.example.unknown",
            mode = "NOT_A_MODE",
            action = "NOT_AN_ACTION",
        )

        val policy = entity.toDomainPolicy()

        // Never silently ALLOW a row we could not parse.
        assertEquals(AppPolicyMode.BLOCK, policy.mode)
        assertEquals(ProtectionAction.HARD_BLOCK, policy.action)
    }
}
