package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Phase 4 Step 4: the app daily-limit enforcement semantics the protection pipeline depends on.
 *
 * The arithmetic already lived in [DefaultPolicyEvaluator]; this step added the condition that it
 * must only run on a *real measurement*. These tests pin both halves: the unchanged
 * `used >= limit` boundary, and the new distinction between "measured 0" and "no measurement".
 */
class ScreenTimeEnforcementPolicyTest {

    private val evaluator = DefaultPolicyEvaluator()
    private val childId = 5L
    private val pkg = "com.example.youtube"

    private fun context(
        appPolicy: AppPolicy?,
        appTimeUsedMinutes: Int?,
        enabled: Boolean = true,
        identityChildId: Long? = childId,
    ) = PolicyContext(
        identity = IdentityContext(identity = UserIdentity.CHILD, childId = identityChildId, confidence = 0.9f),
        settings = PolicySettings(
            enabled = enabled,
            childAction = ProtectionAction.HARD_BLOCK,
            unknownUserAction = ProtectionAction.SOFT_BLOCK,
            noFaceAction = ProtectionAction.ALLOW,
        ),
        foregroundPackage = pkg,
        deviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
        appPolicy = appPolicy,
        appTimeUsedMinutes = appTimeUsedMinutes,
    )

    private fun limitPolicy(minutes: Int?, action: ProtectionAction = ProtectionAction.SOFT_BLOCK) = AppPolicy(
        packageName = pkg,
        mode = AppPolicyMode.LIMIT,
        action = action,
        dailyLimitMinutes = minutes,
        childId = childId,
    )

    private fun ProtectionAction?.orAllow() = this ?: ProtectionAction.ALLOW

    private fun actionOf(decision: PolicyDecision): ProtectionAction =
        (decision as? PolicyDecision.Protect)?.action.orAllow()

    private fun triggerOf(decision: PolicyDecision): PolicyTrigger? =
        (decision as? PolicyDecision.Protect)?.trigger

    // ---- the boundary is unchanged ------------------------------------------

    @Test
    fun usageBelowTheLimitIsAllowed() {
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(limitPolicy(30), 29)))
    }

    @Test
    fun usageExactlyAtTheLimitIsProtect() {
        val decision = evaluator.evaluate(context(limitPolicy(30), 30))

        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(decision))
        assertEquals(PolicyTrigger.SCREEN_TIME_EXCEEDED, triggerOf(decision))
    }

    @Test
    fun usageAboveTheLimitIsProtect() {
        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(evaluator.evaluate(context(limitPolicy(30), 31))))
    }

    @Test
    fun aLimitPolicyWithoutMinutesIsUnlimited() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(limitPolicy(null), appTimeUsedMinutes = 5_000)),
        )
    }

    @Test
    fun aZeroLimitIsReachedByAMeasuredZero() {
        // A real 0-minute limit is reached immediately — 0 is a value, not "unlimited".
        val decision = evaluator.evaluate(context(limitPolicy(0), 0))

        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(decision))
        assertEquals(PolicyTrigger.SCREEN_TIME_EXCEEDED, triggerOf(decision))
    }

    @Test
    fun measuredZeroUnderAPositiveLimitIsAllowed() {
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(limitPolicy(30), 0)))
    }

    // ---- the new availability distinction -----------------------------------

    @Test
    fun noMeasurementSuppressesTheScreenTimeRestriction() {
        // Usage Access not granted (or no attributed usage): usage is unknown, never zero.
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(limitPolicy(30), null)))
    }

    @Test
    fun noMeasurementDoesNotTurnAZeroLimitIntoAReachedOne() {
        // The case that would break if "unknown" were passed as 0.
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(limitPolicy(0), null)))
    }

    // ---- other policy modes are untouched -----------------------------------

    @Test
    fun anAllowedAppIsNotRestrictedByUsage() {
        val allow = AppPolicy(packageName = pkg, mode = AppPolicyMode.ALLOW, action = ProtectionAction.ALLOW, childId = childId)

        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(allow, 5_000)))
    }

    @Test
    fun aBlockedAppIsStillBlockedRegardlessOfUsage() {
        val block = AppPolicy(packageName = pkg, mode = AppPolicyMode.BLOCK, action = ProtectionAction.HARD_BLOCK, childId = childId)

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluator.evaluate(context(block, null))))
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluator.evaluate(context(block, 0))))
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluator.evaluate(context(block, 500))))
    }

    @Test
    fun protectionDisabledNeverEnforcesAScreenTimeLimit() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(limitPolicy(30), appTimeUsedMinutes = 500, enabled = false)),
        )
    }

    // ---- isolation ----------------------------------------------------------

    @Test
    fun aPolicyBelongingToAnotherChildIsNotEnforced() {
        val otherChildsPolicy = limitPolicy(30).copy(childId = 99L)

        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(otherChildsPolicy, appTimeUsedMinutes = 500)),
        )
    }

    @Test
    fun anUnknownIdentityIsUnaffectedByAppUsage() {
        val decision = evaluator.evaluate(
            context(limitPolicy(30), appTimeUsedMinutes = 500, identityChildId = null)
                .copy(identity = IdentityContext(identity = UserIdentity.UNKNOWN)),
        )

        assertEquals("unknown-user policy, not a screen-time decision", ProtectionAction.SOFT_BLOCK, actionOf(decision))
        assertNotEquals("and not the screen-time trigger", PolicyTrigger.SCREEN_TIME_EXCEEDED, triggerOf(decision))
    }
}
