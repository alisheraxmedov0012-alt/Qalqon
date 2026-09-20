package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

class DefaultPolicyEvaluatorTest {

    private val evaluator = DefaultPolicyEvaluator()

    private val enabled = PolicySettings(
        enabled = true,
        activationDelayMs = 3_000L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    private fun context(
        identity: UserIdentity,
        settings: PolicySettings = enabled,
        childId: Long? = if (identity == UserIdentity.CHILD) 1L else null,
        appPolicy: AppPolicy? = null,
        isProtectedApp: Boolean = false,
        appTimeUsedMinutes: Int = 0,
        deviceOwnerMode: DeviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
    ) = PolicyContext(
        identity = IdentityContext(
            identity = identity,
            childId = childId,
            childName = childId?.let { "Child $it" },
            confidence = 0.9f,
        ),
        settings = settings,
        foregroundPackage = "com.example.app",
        deviceOwnerMode = deviceOwnerMode,
        appPolicy = appPolicy,
        isProtectedApp = isProtectedApp,
        appTimeUsedMinutes = appTimeUsedMinutes,
    )

    // 1
    @Test
    fun `parent recognised is always allowed`() {
        val decision = evaluator.evaluate(
            context(UserIdentity.PARENT, isProtectedApp = true),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    // 2
    @Test
    fun `protected app is allowed when protection is disabled`() {
        val decision = evaluator.evaluate(
            context(UserIdentity.CHILD, settings = enabled.copy(enabled = false), isProtectedApp = true),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    // 3
    @Test
    fun `child on protected app uses configured child action`() {
        val decision = evaluator.evaluate(context(UserIdentity.CHILD, isProtectedApp = true))
        assertTrue(decision is PolicyDecision.Protect)
        assertEquals(ProtectionAction.HARD_BLOCK, (decision as PolicyDecision.Protect).action)
    }

    // 4
    @Test
    fun `child on unprotected app is allowed`() {
        val decision = evaluator.evaluate(context(UserIdentity.CHILD))
        assertEquals(PolicyDecision.Allow, decision)
    }

    // 5
    @Test
    fun `unknown user follows unknown policy`() {
        val decision = evaluator.evaluate(context(UserIdentity.UNKNOWN))
        assertTrue(decision is PolicyDecision.Protect)
        assertEquals(ProtectionAction.SOFT_BLOCK, (decision as PolicyDecision.Protect).action)
    }

    // 6
    @Test
    fun `no face follows no-face policy`() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.NO_FACE, isProtectedApp = true)),
        )

        val blocking = enabled.copy(noFaceAction = ProtectionAction.HARD_BLOCK)
        val decision = evaluator.evaluate(
            context(UserIdentity.NO_FACE, settings = blocking, isProtectedApp = true),
        )
        assertEquals(ProtectionAction.HARD_BLOCK, (decision as PolicyDecision.Protect).action)
    }

    // 7
    @Test
    fun `camera obstruction fails safe with configured action`() {
        val decision = evaluator.evaluate(context(UserIdentity.CAMERA_OBSTRUCTED, isProtectedApp = true))
        assertTrue(decision is PolicyDecision.Protect)
    }

    // 8
    @Test
    fun `limited app under the daily limit is allowed`() {
        val policy = AppPolicy(
            packageName = "com.example.app",
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.HARD_BLOCK,
            dailyLimitMinutes = 30,
            childId = 1L,
        )
        val decision = evaluator.evaluate(
            context(UserIdentity.CHILD, appPolicy = policy, appTimeUsedMinutes = 10),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    // 9
    @Test
    fun `limited app over the daily limit applies the configured action`() {
        val policy = AppPolicy(
            packageName = "com.example.app",
            mode = AppPolicyMode.LIMIT,
            action = ProtectionAction.SOFT_BLOCK,
            dailyLimitMinutes = 30,
            childId = 1L,
        )
        val decision = evaluator.evaluate(
            context(UserIdentity.CHILD, appPolicy = policy, appTimeUsedMinutes = 45),
        )
        assertEquals(ProtectionAction.SOFT_BLOCK, (decision as PolicyDecision.Protect).action)
    }

    // 10
    @Test
    fun `blocked app policy applies its action`() {
        val policy = AppPolicy(
            packageName = "com.example.app",
            mode = AppPolicyMode.BLOCK,
            action = ProtectionAction.HARD_BLOCK,
            childId = 1L,
        )
        val decision = evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = policy))
        assertEquals(ProtectionAction.HARD_BLOCK, (decision as PolicyDecision.Protect).action)
    }

    // 11
    @Test
    fun `activation delay is preserved from settings and app policy`() {
        val fromSettings = evaluator.evaluate(context(UserIdentity.CHILD, isProtectedApp = true))
        assertEquals(3_000L, (fromSettings as PolicyDecision.Protect).activationDelayMs)

        val policy = AppPolicy(
            packageName = "com.example.app",
            mode = AppPolicyMode.BLOCK,
            activationDelayMs = 7_000L,
            recoveryDelayMs = 9_000L,
            childId = 1L,
        )
        val fromPolicy = evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = policy))
        assertEquals(7_000L, (fromPolicy as PolicyDecision.Protect).activationDelayMs)
    }

    // 12
    @Test
    fun `recovery delay is preserved from settings and app policy`() {
        val fromSettings = evaluator.evaluate(context(UserIdentity.CHILD, isProtectedApp = true))
        assertEquals(30_000L, (fromSettings as PolicyDecision.Protect).recoveryDelayMs)

        val policy = AppPolicy(
            packageName = "com.example.app",
            mode = AppPolicyMode.BLOCK,
            recoveryDelayMs = 12_000L,
            childId = 1L,
        )
        val fromPolicy = evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = policy))
        assertEquals(12_000L, (fromPolicy as PolicyDecision.Protect).recoveryDelayMs)
    }

    // 13
    @Test
    fun `a policy for another child does not leak`() {
        val otherChildPolicy = AppPolicy(
            packageName = "com.example.app",
            mode = AppPolicyMode.BLOCK,
            childId = 99L,
        )
        val decision = evaluator.evaluate(
            context(UserIdentity.CHILD, childId = 1L, appPolicy = otherChildPolicy),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    // 14
    @Test
    fun `parent recognition is never downgraded to a child decision`() {
        val decision = evaluator.evaluate(
            context(UserIdentity.PARENT, appPolicy = AppPolicy("com.example.app")),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    // 15
    @Test
    fun `warning action yields a warn decision without enforcement`() {
        val warning = enabled.copy(childAction = ProtectionAction.WARNING)
        val decision = evaluator.evaluate(
            context(UserIdentity.CHILD, settings = warning, isProtectedApp = true),
        )
        assertTrue(decision is PolicyDecision.Warn)
    }

    // 16
    @Test
    fun `parent device skips child enforcement unless enabled`() {
        val decision = evaluator.evaluate(
            context(
                UserIdentity.CHILD,
                isProtectedApp = true,
                deviceOwnerMode = DeviceOwnerMode.PARENT_DEVICE,
            ),
        )
        assertEquals(PolicyDecision.Allow, decision)

        val enforcing = enabled.copy(parentDeviceChildPolicyEnabled = true)
        val enforced = evaluator.evaluate(
            context(
                UserIdentity.CHILD,
                settings = enforcing,
                isProtectedApp = true,
                deviceOwnerMode = DeviceOwnerMode.PARENT_DEVICE,
            ),
        )
        assertTrue(enforced is PolicyDecision.Protect)
    }
}
