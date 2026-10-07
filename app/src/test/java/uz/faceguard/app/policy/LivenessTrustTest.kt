package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessPolicy
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.LivenessTrust
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Stage 3 (Anti-Spoof / Liveness): the explicit identity + liveness authorization policy.
 *
 * These pin the combination rule — a recognised face is only meaningful together with the
 * liveness signal, and a positive SPOOF determination must never inherit a recognised
 * identity's policy. They prove the *decision logic*; real spoofing efficacy needs a model
 * and physical devices and is out of Stage 3 scope (see the report).
 */
class LivenessTrustTest {

    private val evaluator = DefaultPolicyEvaluator()

    // --------------------------------------------------------- classification

    @Test
    fun trustOfClassifiesEveryLivenessStateExplicitly() {
        assertEquals(LivenessTrust.LIVE, LivenessPolicy.trustOf(LivenessState.LIVE))
        assertEquals(LivenessTrust.SPOOF, LivenessPolicy.trustOf(LivenessState.SPOOF))
        assertEquals(LivenessTrust.UNDECIDED, LivenessPolicy.trustOf(LivenessState.UNKNOWN))
        assertEquals(LivenessTrust.UNDECIDED, LivenessPolicy.trustOf(LivenessState.UNSTABLE))
        assertEquals(LivenessTrust.UNDECIDED, LivenessPolicy.trustOf(LivenessState.NO_FACE))
    }

    @Test
    fun onlyAPositiveSpoofDeterminationOverridesIdentity() {
        assertTrue(LivenessPolicy.overridesIdentity(LivenessState.SPOOF))
        listOf(LivenessState.LIVE, LivenessState.UNKNOWN, LivenessState.UNSTABLE, LivenessState.NO_FACE)
            .forEach { assertFalse("$it must not override identity", LivenessPolicy.overridesIdentity(it)) }
    }

    @Test
    fun onlyALiveDeterminationIsTrustedLive() {
        assertTrue(LivenessPolicy.isTrustedLive(LivenessState.LIVE))
        listOf(LivenessState.SPOOF, LivenessState.UNKNOWN, LivenessState.UNSTABLE, LivenessState.NO_FACE)
            .forEach { assertFalse("$it is not trusted live", LivenessPolicy.isTrustedLive(it)) }
    }

    // --------------------------------------------------------- decision matrix

    private fun settings(
        enabled: Boolean = true,
        spoof: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ) = PolicySettings(
        enabled = enabled,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.SOFT_BLOCK,
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

    // Live identity authorizes normally.

    @Test
    fun liveParentIsAllowed() {
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(UserIdentity.PARENT, LivenessState.LIVE)))
    }

    @Test
    fun liveChildAppliesTheChildPolicy() {
        assertEquals(
            ProtectionAction.HARD_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.LIVE))),
        )
    }

    @Test
    fun liveUnknownAppliesTheUnknownPolicy() {
        assertEquals(
            ProtectionAction.SOFT_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.UNKNOWN, LivenessState.LIVE))),
        )
    }

    // Spoof overrides every identity, fail-closed.

    @Test
    fun aSpoofedParentIsNotAllowed() {
        val decision = evaluator.evaluate(context(UserIdentity.PARENT, LivenessState.SPOOF))
        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(decision))
        assertFalse("a spoofed parent must never be allowed", decision == PolicyDecision.Allow)
    }

    @Test
    fun aSpoofedChildDoesNotInheritTheTrustedChildPath() {
        // The child branch would HARD_BLOCK; the spoof gate must take precedence (its own
        // configured action), so a spoofed child never becomes a "live child".
        assertEquals(
            ProtectionAction.SOFT_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.SPOOF))),
        )
    }

    @Test
    fun aSpoofedUnknownFollowsTheSpoofAction() {
        assertEquals(
            ProtectionAction.SOFT_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.UNKNOWN, LivenessState.SPOOF))),
        )
    }

    @Test
    fun theSpoofActionIsConfigurable() {
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
    fun aSpoofedPresentationIsIgnoredWhenProtectionIsOff() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.SPOOF, settings(enabled = false))),
        )
    }

    // Undecided liveness does not override the identity (documented policy).

    @Test
    fun undecidedLivenessLeavesTheIdentityDecisionUnchanged() {
        listOf(LivenessState.UNKNOWN, LivenessState.UNSTABLE).forEach { liveness ->
            assertEquals(
                "child + $liveness keeps the child policy",
                ProtectionAction.HARD_BLOCK,
                actionOf(evaluator.evaluate(context(UserIdentity.CHILD, liveness))),
            )
            assertEquals(
                "parent + $liveness keeps the parent's access",
                PolicyDecision.Allow,
                evaluator.evaluate(context(UserIdentity.PARENT, liveness)),
            )
        }
    }

    @Test
    fun aNoFaceLivenessWithAnUnknownIdentityFollowsTheNoFacePolicy() {
        assertEquals(
            ProtectionAction.SOFT_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.NO_FACE, LivenessState.NO_FACE))),
        )
    }

    // --------------------------------------------------------- wiring contract

    @Test
    fun theEvaluatorUsesTheExplicitLivenessPolicy() {
        // A source-level contract: the spoof gate must be the explicit policy, not a bare
        // equality, so the rule cannot drift from LivenessPolicy.
        val file = java.io.File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/policy/DefaultPolicyEvaluator.kt")
        assertTrue(file.isFile)
        val source = file.readText()
        assertTrue(source.contains("LivenessPolicy.overridesIdentity(context.liveness)"))
        assertFalse(source.contains("LivenessState.SPOOF"))
    }

    private fun repoRoot(): java.io.File {
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (java.io.File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }
}
