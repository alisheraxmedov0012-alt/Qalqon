package uz.faceguard.app.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.feature.home.ChildSection
import uz.faceguard.app.feature.home.DashboardUiState
import uz.faceguard.app.feature.home.PolicyCounts
import uz.faceguard.app.feature.home.RECENT_EVENT_LIMIT
import uz.faceguard.app.feature.home.configuredLimits
import uz.faceguard.app.feature.home.eventLabelRes
import uz.faceguard.app.feature.home.identityLabelRes
import uz.faceguard.app.feature.home.isChildScopedCurrent
import uz.faceguard.app.feature.home.livenessLabelRes
import uz.faceguard.app.feature.home.policyCounts
import uz.faceguard.app.feature.home.policyModeLabelRes
import uz.faceguard.app.feature.home.protectionStateLabelRes
import uz.faceguard.app.feature.home.recentEventSummaries
import uz.faceguard.app.feature.home.resolveSelectedChild
import uz.faceguard.app.feature.home.restrictionLevelLabelRes

/**
 * Phase 10: the pure dashboard presentation mapping and aggregation helpers.
 * Runs on the JVM; no Android framework, no fabricated data.
 */
class DashboardMappingTest {

    // ---- Protection / identity / liveness / state mapping -------------------

    @Test
    fun everySupportedIdentityMapsToItsOwnLabel() {
        assertEquals(R.string.dashboard_identity_parent, identityLabelRes(UserIdentity.PARENT))
        assertEquals(R.string.dashboard_identity_child, identityLabelRes(UserIdentity.CHILD))
        assertEquals(R.string.dashboard_identity_unknown, identityLabelRes(UserIdentity.UNKNOWN))
        assertEquals(R.string.dashboard_identity_no_face, identityLabelRes(UserIdentity.NO_FACE))
        assertEquals(R.string.dashboard_identity_obstructed, identityLabelRes(UserIdentity.CAMERA_OBSTRUCTED))
        assertEquals(R.string.dashboard_identity_none, identityLabelRes(null))
    }

    @Test
    fun everyProtectionStateMapsToItsExistingLabel() {
        assertEquals(R.string.protection_state_unprotected, protectionStateLabelRes(ProtectionState.UNPROTECTED))
        assertEquals(R.string.protection_state_soft, protectionStateLabelRes(ProtectionState.SOFT_BLOCKED))
        assertEquals(R.string.protection_state_hard, protectionStateLabelRes(ProtectionState.HARD_BLOCKED))
        assertEquals(R.string.protection_state_recovering, protectionStateLabelRes(ProtectionState.RECOVERING))
    }

    @Test
    fun livenessIsSurfacedOnlyWhenItIsActionable() {
        assertEquals(R.string.dashboard_liveness_live, livenessLabelRes(LivenessState.LIVE))
        assertEquals(R.string.dashboard_liveness_spoof, livenessLabelRes(LivenessState.SPOOF))
        assertNull(livenessLabelRes(LivenessState.UNKNOWN))
        assertNull(livenessLabelRes(LivenessState.NO_FACE))
        assertNull(livenessLabelRes(LivenessState.UNSTABLE))
        assertNull(livenessLabelRes(null))
    }

    @Test
    fun everyEventTypeMapsToAnExistingActivityLabel() {
        val mapping = mapOf(
            ActivityEventType.CHILD_RECOGNIZED to R.string.activity_child_recognized,
            ActivityEventType.PARENT_RECOGNIZED to R.string.activity_parent_recognized,
            ActivityEventType.UNKNOWN_USER to R.string.activity_unknown_user,
            ActivityEventType.NO_FACE to R.string.activity_no_face,
            ActivityEventType.PROTECTED_APP_ENTERED to R.string.activity_protected_app_entered,
            ActivityEventType.CHILD_BLOCKED to R.string.activity_child_blocked,
            ActivityEventType.PROTECTION_RELEASED to R.string.activity_protection_released,
            ActivityEventType.PARENT_UNLOCKED to R.string.activity_parent_unlocked,
            ActivityEventType.EMERGENCY_UNLOCK to R.string.activity_emergency_unlock,
        )
        assertEquals(ActivityEventType.entries.size, mapping.size)
        mapping.forEach { (type, res) -> assertEquals(res, eventLabelRes(type)) }
    }

    @Test
    fun restrictionLevelMapsAndUnknownNeverFabricatesALevel() {
        assertEquals(R.string.level_low, restrictionLevelLabelRes(RestrictionLevel.LOW))
        assertEquals(R.string.level_medium, restrictionLevelLabelRes(RestrictionLevel.MEDIUM))
        assertEquals(R.string.level_high, restrictionLevelLabelRes(RestrictionLevel.HIGH))
        assertEquals(R.string.dashboard_value_unknown, restrictionLevelLabelRes(null))
    }

    @Test
    fun policyModeLabelsReuseExistingProductTerminology() {
        assertEquals(R.string.policy_allow, policyModeLabelRes(AppPolicyMode.ALLOW))
        assertEquals(R.string.child_policy_mode_limit, policyModeLabelRes(AppPolicyMode.LIMIT))
        assertEquals(R.string.policy_hard_block, policyModeLabelRes(AppPolicyMode.BLOCK))
    }

    // ---- Policy summary -----------------------------------------------------

    @Test
    fun policyCountsSplitAllowLimitAndBlock() {
        val policies = listOf(
            policy("a", AppPolicyMode.ALLOW),
            policy("b", AppPolicyMode.LIMIT, limit = 30),
            policy("c", AppPolicyMode.BLOCK),
            policy("d", AppPolicyMode.BLOCK),
        )

        val counts = policyCounts(policies)

        assertEquals(1, counts.allow)
        assertEquals(1, counts.limit)
        assertEquals(2, counts.block)
        assertEquals(4, counts.total)
    }

    @Test
    fun configuredLimitsCarryTheConfiguredValueAndNoUsage() {
        val policies = listOf(
            policy("com.example.youtube", AppPolicyMode.LIMIT, limit = 30),
            policy("com.example.games", AppPolicyMode.LIMIT, limit = null),
            policy("com.example.other", AppPolicyMode.BLOCK),
        )

        val limits = configuredLimits(policies)

        assertEquals(2, limits.size)
        assertEquals("com.example.games", limits[0].packageName)
        assertNull(limits[0].dailyLimitMinutes)
        assertEquals("com.example.youtube", limits[1].packageName)
        assertEquals(30, limits[1].dailyLimitMinutes)
    }

    // ---- Activity summary ---------------------------------------------------

    @Test
    fun recentEventsAreBoundedAndKeepTheirOrder() {
        val events = (1..8).map { index ->
            ActivityEvent(id = index.toLong(), accountId = 1L, type = ActivityEventType.CHILD_BLOCKED, at = 1_000L - index)
        }

        val summary = recentEventSummaries(events)

        assertEquals(RECENT_EVENT_LIMIT, summary.size)
        assertEquals(ActivityEventType.CHILD_BLOCKED, summary.first().type)
    }

    @Test
    fun emptyActivityProducesAnEmptySummaryNotFakeEntries() {
        assertTrue(recentEventSummaries(emptyList()).isEmpty())
    }

    // ---- Child selection ----------------------------------------------------

    private fun child(id: Long, name: String = "Child $id") = ChildProfile(
        id = id,
        accountId = 1L,
        childName = name,
        isFaceEnrolled = true,
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    @Test
    fun aSingleChildIsSelectedByDefault() {
        assertEquals(3L, resolveSelectedChild(listOf(child(3L)), current = null))
    }

    @Test
    fun theCurrentSelectionWinsWhileItStillExists() {
        val children = listOf(child(1L), child(2L))
        assertEquals(2L, resolveSelectedChild(children, current = 2L))
    }

    @Test
    fun aForeignOrDeletedSelectionFallsBackToTheFirstChild() {
        val children = listOf(child(1L), child(2L))
        assertEquals(1L, resolveSelectedChild(children, current = 99L))
    }

    @Test
    fun anEmptyChildListResolvesToNoSelection() {
        assertNull(resolveSelectedChild(emptyList(), current = 1L))
    }

    @Test
    fun childScopedDataIsCurrentOnlyForTheSelectedChild() {
        assertTrue(isChildScopedCurrent(loadedChildId = 5L, selectedChildId = 5L))
        assertFalse("a previous child's data must not be shown as the new child's", isChildScopedCurrent(5L, 6L))
        assertFalse(isChildScopedCurrent(null, 6L))
        assertFalse(isChildScopedCurrent(5L, null))
    }

    // ---- State invariants ---------------------------------------------------

    @Test
    fun usageIsNeverReportedAsAvailableInThisPhase() {
        assertFalse(DashboardUiState().usageAvailable)
        assertFalse(
            DashboardUiState(
                child = ChildSection(
                    childId = 1L,
                    name = "A",
                    faceEnrolled = true,
                    level = RestrictionLevel.MEDIUM,
                    policies = PolicyCounts(limit = 1),
                    limits = emptyList(),
                    policiesLoading = false,
                ),
            ).usageAvailable,
        )
    }

    @Test
    fun enforcementIsReadyOnlyWhenEveryCapabilityIsGranted() {
        assertFalse(DashboardUiState().enforcementReady)
        assertFalse(DashboardUiState(overlayGranted = true, usageAccessGranted = true).enforcementReady)
        assertTrue(
            DashboardUiState(
                overlayGranted = true,
                usageAccessGranted = true,
                accessibilityEnabled = true,
            ).enforcementReady,
        )
    }

    @Test
    fun anEmptyDashboardDoesNotPretendToHaveAChild() {
        val state = DashboardUiState()
        assertFalse(state.hasChild)
        assertFalse(state.hasMultipleChildren)
        assertNull(state.child)
    }

    @Test
    fun distinctDashboardsAreDistinctStates() {
        assertNotEquals(DashboardUiState(), DashboardUiState(protectionEnabled = true))
    }

    private fun policy(pkg: String, mode: AppPolicyMode, limit: Int? = null) = AppPolicy(
        packageName = pkg,
        mode = mode,
        action = when (mode) {
            AppPolicyMode.ALLOW -> ProtectionAction.ALLOW
            AppPolicyMode.LIMIT -> ProtectionAction.SOFT_BLOCK
            AppPolicyMode.BLOCK -> ProtectionAction.HARD_BLOCK
        },
        dailyLimitMinutes = limit,
    )
}
