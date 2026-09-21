package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.feature.policy.PolicyDisplay
import uz.faceguard.app.feature.policy.defaultAction
import uz.faceguard.app.feature.policy.displayFor

/**
 * Pure mapping used by the Group 4 policy screen: explicit vs inherited display
 * and the fixed action each mode maps to. No Android, no UI.
 */
class ChildPolicyDisplayMappingTest {

    private val pkg = "com.example.youtube"

    @Test
    fun noExplicitPolicy_followsTheInheritedGlobalRule() {
        assertEquals(PolicyDisplay.DEFAULT_ALLOW, displayFor(null, isGlobalProtected = false))
        assertEquals(PolicyDisplay.DEFAULT_BLOCK, displayFor(null, isGlobalProtected = true))
    }

    @Test
    fun explicitPolicy_winsOverTheInheritedRule() {
        assertEquals(
            PolicyDisplay.EXPLICIT_ALLOW,
            displayFor(AppPolicy(pkg, AppPolicyMode.ALLOW), isGlobalProtected = true),
        )
        assertEquals(
            PolicyDisplay.EXPLICIT_LIMIT,
            displayFor(AppPolicy(pkg, AppPolicyMode.LIMIT), isGlobalProtected = true),
        )
        assertEquals(
            PolicyDisplay.EXPLICIT_BLOCK,
            displayFor(AppPolicy(pkg, AppPolicyMode.BLOCK), isGlobalProtected = false),
        )
    }

    @Test
    fun eachModeMapsToTheEvaluatorsDocumentedAction() {
        assertEquals(ProtectionAction.ALLOW, AppPolicyMode.ALLOW.defaultAction())
        assertEquals(ProtectionAction.SOFT_BLOCK, AppPolicyMode.LIMIT.defaultAction())
        assertEquals(ProtectionAction.HARD_BLOCK, AppPolicyMode.BLOCK.defaultAction())
    }
}
