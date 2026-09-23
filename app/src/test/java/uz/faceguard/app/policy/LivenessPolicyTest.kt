package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Group 9: identity and liveness are separate policy inputs. LIVE leaves the
 * existing identity behaviour untouched; SPOOF is a safe gate that runs *before*
 * the identity switch, so a recognised-but-spoofed parent/child is never trusted.
 */
class LivenessPolicyTest {

    private val evaluator = DefaultPolicyEvaluator()

    private fun settings(
        enabled: Boolean = true,
        spoof: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ) = PolicySettings(
        enabled = enabled,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        spoofAction = spoof,
    )

    private fun context(
        identity: UserIdentity,
        liveness: LivenessState,
        settings: PolicySettings = settings(),
        isProtectedApp: Boolean = true,
    ) = PolicyContext(
        identity = IdentityContext(
            identity = identity,
            childId = if (identity == UserIdentity.CHILD) 1L else null,
            childName = if (identity == UserIdentity.CHILD) "Child 1" else null,
        ),
        settings = settings,
        liveness = liveness,
        foregroundPackage = "com.example.app",
        isProtectedApp = isProtectedApp,
    )

    private fun actionOf(decision: PolicyDecision): ProtectionAction? =
        (decision as? PolicyDecision.Protect)?.action

    @Test
    fun `the spoof action defaults to a recoverable soft block`() {
        assertEquals(ProtectionAction.SOFT_BLOCK, PolicySettings().spoofAction)
    }

    @Test
    fun `live child on a protected app keeps the existing child action`() {
        val decision = evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.LIVE))

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(decision))
    }

    @Test
    fun `live parent is always allowed`() {
        val decision = evaluator.evaluate(context(UserIdentity.PARENT, LivenessState.LIVE))

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun `a spoofed child identity is not trusted and uses the spoof action`() {
        val decision = evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.SPOOF))

        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(decision))
    }

    @Test
    fun `a spoofed parent does not unlock`() {
        val decision = evaluator.evaluate(context(UserIdentity.PARENT, LivenessState.SPOOF))

        assertEquals(
            "a recognised parent behind a spoof must not inherit the parent override",
            ProtectionAction.SOFT_BLOCK,
            actionOf(decision),
        )
    }

    @Test
    fun `the spoof action is configurable`() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.SPOOF, settings(spoof = ProtectionAction.ALLOW))),
        )
        assertEquals(
            ProtectionAction.HARD_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.SPOOF, settings(spoof = ProtectionAction.HARD_BLOCK)))),
        )
    }

    @Test
    fun `unstable liveness does not override the identity decision`() {
        val decision = evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.UNSTABLE))

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(decision))
    }

    @Test
    fun `unknown liveness falls through to the identity decision`() {
        val decision = evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.UNKNOWN))

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(decision))
    }

    @Test
    fun `no face follows the existing no-face policy`() {
        val decision = evaluator.evaluate(context(UserIdentity.NO_FACE, LivenessState.NO_FACE))

        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun `an unknown user follows the existing unknown policy`() {
        val decision = evaluator.evaluate(context(UserIdentity.UNKNOWN, LivenessState.LIVE))

        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(decision))
    }

    @Test
    fun `disabled protection ignores the spoof gate`() {
        val decision = evaluator.evaluate(
            context(UserIdentity.CHILD, LivenessState.SPOOF, settings(enabled = false)),
        )

        assertTrue(decision is PolicyDecision.Allow)
    }
}
