package uz.faceguard.app.tests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.DeviceOwnerMode
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Phase 13: an exhaustive precedence matrix for the policy engine.
 *
 * The evaluator is the single decision point, so this nails down every
 * identity x liveness x app-policy combination instead of sampling a few paths.
 */
class Phase13PolicyMatrixTest {

    private val evaluator = DefaultPolicyEvaluator()

    private val settings = PolicySettings(
        enabled = true,
        activationDelayMs = 0L,
        childAction = ProtectionAction.HARD_BLOCK,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        spoofAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    private fun context(
        identity: UserIdentity,
        liveness: LivenessState = LivenessState.UNKNOWN,
        settings: PolicySettings = this.settings,
        childId: Long? = if (identity == UserIdentity.CHILD) 5L else null,
        appPolicy: AppPolicy? = null,
        isProtectedApp: Boolean = false,
        appTimeUsedMinutes: Int = 0,
        deviceOwnerMode: DeviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
    ) = PolicyContext(
        identity = IdentityContext(identity = identity, childId = childId, childName = "Vali", confidence = 0.9f),
        settings = settings,
        liveness = liveness,
        foregroundPackage = "com.example.target",
        deviceOwnerMode = deviceOwnerMode,
        appPolicy = appPolicy,
        isProtectedApp = isProtectedApp,
        appTimeUsedMinutes = appTimeUsedMinutes,
    )

    private fun actionOf(decision: PolicyDecision): ProtectionAction? =
        (decision as? PolicyDecision.Protect)?.action

    private fun policy(mode: AppPolicyMode, limit: Int? = null, childId: Long? = 5L) = AppPolicy(
        packageName = "com.example.target",
        mode = mode,
        action = when (mode) {
            AppPolicyMode.ALLOW -> ProtectionAction.ALLOW
            AppPolicyMode.LIMIT -> ProtectionAction.SOFT_BLOCK
            AppPolicyMode.BLOCK -> ProtectionAction.HARD_BLOCK
        },
        dailyLimitMinutes = limit,
        childId = childId,
    )

    // ---- disabled -----------------------------------------------------------

    @Test
    fun protectionDisabledAllowsEveryIdentityAndLiveness() {
        val off = settings.copy(enabled = false)

        UserIdentity.entries.forEach { identity ->
            LivenessState.entries.forEach { live ->
                assertEquals(
                    "$identity/$live",
                    PolicyDecision.Allow,
                    evaluator.evaluate(context(identity, live, settings = off, isProtectedApp = true)),
                )
            }
        }
    }

    // ---- parent -------------------------------------------------------------

    @Test
    fun aParentIsAllowedOnABlockedAppForEveryNonSpoofLiveness() {
        listOf(LivenessState.LIVE, LivenessState.UNKNOWN, LivenessState.NO_FACE, LivenessState.UNSTABLE).forEach { live ->
            assertEquals(
                "parent/$live",
                PolicyDecision.Allow,
                evaluator.evaluate(
                    context(UserIdentity.PARENT, live, appPolicy = policy(AppPolicyMode.BLOCK), isProtectedApp = true),
                ),
            )
        }
    }

    // ---- child --------------------------------------------------------------

    @Test
    fun explicitChildPoliciesDecideTheAction() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = policy(AppPolicyMode.ALLOW))),
        )
        assertEquals(
            ProtectionAction.HARD_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = policy(AppPolicyMode.BLOCK)))),
        )
    }

    @Test
    fun aLimitPolicyIsAllowedUntilAndBlockedAtTheConfiguredLimit() {
        val limited = policy(AppPolicyMode.LIMIT, limit = 30)

        assertEquals(
            "just under the limit",
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = limited, appTimeUsedMinutes = 29)),
        )
        assertEquals(
            "exactly at the limit blocks",
            ProtectionAction.SOFT_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = limited, appTimeUsedMinutes = 30))),
        )
        assertEquals(
            "beyond the limit blocks",
            ProtectionAction.SOFT_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = limited, appTimeUsedMinutes = 31))),
        )
    }

    @Test
    fun aLimitWithoutAConfiguredDurationIsUnlimited() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(
                context(UserIdentity.CHILD, appPolicy = policy(AppPolicyMode.LIMIT, limit = null), appTimeUsedMinutes = 5_000),
            ),
        )
    }

    @Test
    fun withoutAnExplicitPolicyTheGlobalProtectedCatalogDecides() {
        assertEquals(
            ProtectionAction.HARD_BLOCK,
            actionOf(evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = null, isProtectedApp = true))),
        )
        assertEquals(
            "an unprotected app is allowed for a child",
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, appPolicy = null, isProtectedApp = false)),
        )
    }

    @Test
    fun aPolicyBelongingToAnotherChildIsNeverApplied() {
        val foreign = policy(AppPolicyMode.BLOCK, childId = 6L)

        assertEquals(
            "child 5 must not inherit child 6's explicit override",
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, childId = 5L, appPolicy = foreign, isProtectedApp = false)),
        )
    }

    @Test
    fun aParentDeviceSkipsTheChildPolicyUnlessEnabled() {
        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(
                context(
                    UserIdentity.CHILD,
                    appPolicy = policy(AppPolicyMode.BLOCK),
                    deviceOwnerMode = DeviceOwnerMode.PARENT_DEVICE,
                ),
            ),
        )
        assertEquals(
            ProtectionAction.HARD_BLOCK,
            actionOf(
                evaluator.evaluate(
                    context(
                        UserIdentity.CHILD,
                        appPolicy = policy(AppPolicyMode.BLOCK),
                        settings = settings.copy(parentDeviceChildPolicyEnabled = true),
                        deviceOwnerMode = DeviceOwnerMode.PARENT_DEVICE,
                    ),
                ),
            ),
        )
    }

    // ---- unknown / no-face / obstructed -------------------------------------

    @Test
    fun fallbackIdentitiesFollowTheirConfiguredActions() {
        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(evaluator.evaluate(context(UserIdentity.UNKNOWN))))
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(UserIdentity.NO_FACE)))
        assertEquals(ProtectionAction.SOFT_BLOCK, actionOf(evaluator.evaluate(context(UserIdentity.CAMERA_OBSTRUCTED))))
    }

    @Test
    fun fallbackActionsAreConfigurable() {
        val custom = settings.copy(
            unknownUserAction = ProtectionAction.HARD_BLOCK,
            noFaceAction = ProtectionAction.HARD_BLOCK,
            obstructionAction = ProtectionAction.ALLOW,
        )

        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluator.evaluate(context(UserIdentity.UNKNOWN, settings = custom))))
        assertEquals(ProtectionAction.HARD_BLOCK, actionOf(evaluator.evaluate(context(UserIdentity.NO_FACE, settings = custom))))
        assertEquals(PolicyDecision.Allow, evaluator.evaluate(context(UserIdentity.CAMERA_OBSTRUCTED, settings = custom)))
    }

    @Test
    fun aWarningActionNeverRestricts() {
        val warnOnly = settings.copy(unknownUserAction = ProtectionAction.WARNING)

        assertTrue(evaluator.evaluate(context(UserIdentity.UNKNOWN, settings = warnOnly)) is PolicyDecision.Warn)
    }

    // ---- spoof --------------------------------------------------------------

    @Test
    fun spoofIsGatedBeforeEveryIdentityIncludingParentAndChild() {
        listOf(UserIdentity.PARENT, UserIdentity.CHILD, UserIdentity.UNKNOWN, UserIdentity.NO_FACE).forEach { identity ->
            val decision = evaluator.evaluate(
                context(identity, LivenessState.SPOOF, isProtectedApp = true, appPolicy = policy(AppPolicyMode.ALLOW)),
            )
            assertEquals(
                "a spoofed $identity must not inherit its own policy",
                ProtectionAction.SOFT_BLOCK,
                actionOf(decision),
            )
        }
    }

    @Test
    fun nonSpoofLivenessNeverChangesAnIdentityDecision() {
        UserIdentity.entries.forEach { identity ->
            val baseline = evaluator.evaluate(context(identity, LivenessState.UNKNOWN, appPolicy = policy(AppPolicyMode.BLOCK)))
            listOf(LivenessState.LIVE, LivenessState.NO_FACE, LivenessState.UNSTABLE, LivenessState.UNKNOWN).forEach { live ->
                assertEquals(
                    "$identity/$live must behave like the baseline",
                    baseline,
                    evaluator.evaluate(context(identity, live, appPolicy = policy(AppPolicyMode.BLOCK))),
                )
            }
        }
    }

    @Test
    fun aSpoofActionIsConfigurable() {
        val allowSpoof = settings.copy(spoofAction = ProtectionAction.ALLOW)

        assertEquals(
            PolicyDecision.Allow,
            evaluator.evaluate(context(UserIdentity.CHILD, LivenessState.SPOOF, settings = allowSpoof)),
        )
    }
}
