package uz.faceguard.app.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.feature.child.CHILD_DETAIL_CONTROLS
import uz.faceguard.app.feature.child.ChildAttentionKind
import uz.faceguard.app.feature.child.ChildDetailControl
import uz.faceguard.app.feature.child.childAttentionItems
import uz.faceguard.app.feature.child.childControlDescriptionRes
import uz.faceguard.app.feature.child.childControlLabelRes
import uz.faceguard.app.feature.child.childProtectionStatus
import uz.faceguard.app.feature.home.DashboardUiState
import uz.faceguard.app.feature.home.HomeProtectionStatus
import uz.faceguard.app.feature.home.homeProtectionStatus

/**
 * UI/UX redesign Phase 4: the Child Detail hub's presentation mapping.
 *
 * Pins the control hub's shape, the child-scoped attention rules, and — most
 * importantly — that the hub's protection status agrees exactly with Home's, so the
 * two screens can never disagree about the same runtime state.
 */
class ChildDetailPresentationTest {

    // ------------------------------------------------------------- protection

    @Test
    fun theChildStatusAgreesWithTheHomeStatusForEveryCombination() {
        for (enabled in listOf(false, true)) {
            for (state in ProtectionState.entries) {
                for (ready in listOf(false, true)) {
                    val expected = homeProtectionStatus(
                        DashboardUiState(
                            protectionEnabled = enabled,
                            protectionState = state,
                            overlayGranted = ready,
                            usageAccessGranted = ready,
                            accessibilityEnabled = ready,
                        ),
                    )
                    assertEquals(
                        "enabled=$enabled state=$state ready=$ready",
                        expected,
                        childProtectionStatus(enabled, state, ready),
                    )
                }
            }
        }
    }

    @Test
    fun disabledProtectionIsOffEvenWithABlockState() {
        assertEquals(
            HomeProtectionStatus.OFF,
            childProtectionStatus(
                protectionEnabled = false,
                protectionState = ProtectionState.HARD_BLOCKED,
                enforcementReady = true,
            ),
        )
    }

    @Test
    fun aLiveBlockOutranksAMissingCapability() {
        assertEquals(
            HomeProtectionStatus.BLOCKING,
            childProtectionStatus(true, ProtectionState.SOFT_BLOCKED, enforcementReady = false),
        )
    }

    @Test
    fun enabledWithoutEveryCapabilityIsSetupRequired() {
        assertEquals(
            HomeProtectionStatus.SETUP_REQUIRED,
            childProtectionStatus(true, ProtectionState.UNPROTECTED, enforcementReady = false),
        )
    }

    // --------------------------------------------------------------- controls

    @Test
    fun theHubOffersExactlyTheSixChildScopedControlsInOrder() {
        assertEquals(
            listOf(
                ChildDetailControl.APPS,
                ChildDetailControl.SCREEN_TIME,
                ChildDetailControl.SCHEDULE,
                ChildDetailControl.EYE_SAFETY,
                ChildDetailControl.FACE,
                ChildDetailControl.REQUESTS,
            ),
            CHILD_DETAIL_CONTROLS,
        )
    }

    @Test
    fun everyControlHasALabelAndADescription() {
        ChildDetailControl.entries.forEach { control ->
            assertTrue("${control.name} label", childControlLabelRes(control) != 0)
            assertTrue("${control.name} description", childControlDescriptionRes(control) != 0)
        }
    }

    // -------------------------------------------------------------- attention

    @Test
    fun aHealthyChildHasNoAttentionItems() {
        assertTrue(
            childAttentionItems(
                protectionStatus = HomeProtectionStatus.ACTIVE,
                faceEnrolled = true,
            ).isEmpty(),
        )
    }

    @Test
    fun aMissingFaceIsSurfaced() {
        val items = childAttentionItems(HomeProtectionStatus.ACTIVE, faceEnrolled = false)
        assertEquals(ChildAttentionKind.FACE_NOT_ENROLLED, items.single().kind)
    }

    @Test
    fun protectionSetupIncompleteIsSurfaced() {
        val items = childAttentionItems(HomeProtectionStatus.SETUP_REQUIRED, faceEnrolled = true)
        assertEquals(ChildAttentionKind.PROTECTION_SETUP_REQUIRED, items.single().kind)
    }

    @Test
    fun bothConditionsAreReportedWithTheMostUrgentFirst() {
        val items = childAttentionItems(HomeProtectionStatus.SETUP_REQUIRED, faceEnrolled = false)
        assertEquals(
            listOf(
                ChildAttentionKind.PROTECTION_SETUP_REQUIRED,
                ChildAttentionKind.FACE_NOT_ENROLLED,
            ),
            items.map { it.kind },
        )
    }

    @Test
    fun offlineProtectionIsNotAnAttentionItemByItself() {
        // Protection simply being off is a state, not an actionable issue.
        assertTrue(childAttentionItems(HomeProtectionStatus.OFF, faceEnrolled = true).isEmpty())
        assertFalse(childAttentionItems(HomeProtectionStatus.OFF, faceEnrolled = true).isNotEmpty())
    }
}
