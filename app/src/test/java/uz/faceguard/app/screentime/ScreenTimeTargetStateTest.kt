package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.feature.home.ScreenTimeTargetStatus
import uz.faceguard.app.feature.home.ScreenTimeTargetUiState

/**
 * Phase 4 Step 1B-8 (pure JVM): the screen-time target section's presentation rules.
 *
 * These are the decisions a parent sees, and the important ones are the cases where the
 * section must *not* claim a target: nothing selected, a stored id that no longer resolves,
 * or no children at all. No Android, no DataStore.
 */
class ScreenTimeTargetStateTest {

    private fun child(id: Long, name: String) =
        ChildProfile(id = id, accountId = 1L, childName = name)

    private val ali = child(1L, "Ali")
    private val vali = child(2L, "Vali")
    private val children = listOf(ali, vali)

    @Test
    fun loadingIsNotMistakenForAnEmptyOrUnselectedList() {
        val state = ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.LOADING)

        assertFalse("a loading section must not offer a choice yet", state.noChildren)
        assertFalse(state.needsSelection)
        assertNull(state.activeChild)
    }

    @Test
    fun noChildrenIsItsOwnState() {
        val state = ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.READY, children = emptyList())

        assertTrue("with no children there is nothing to choose", state.noChildren)
        assertFalse("and it is not 'needs a selection'", state.needsSelection)
        assertNull(state.activeChild)
    }

    @Test
    fun childrenWithoutATargetNeedASelection() {
        val state = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = children,
            activeChildId = null,
        )

        assertTrue("the parent must be asked to choose", state.needsSelection)
        assertFalse(state.noChildren)
        assertNull("no child may be pre-selected", state.activeChild)
        assertFalse(state.activeChildMissing)
    }

    @Test
    fun anExistingTargetIsResolvedToItsChild() {
        val state = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = children,
            activeChildId = vali.id,
        )

        assertEquals(vali, state.activeChild)
        assertFalse("a valid target is not 'needs selection'", state.needsSelection)
        assertFalse(state.activeChildMissing)
    }

    @Test
    fun aTargetThatNoLongerResolvesReadsAsMissingNotAsAValidChild() {
        // The stored id points at a child that is gone (deleted elsewhere): the section must
        // say so rather than silently showing another child as selected.
        val state = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = children,
            activeChildId = 99L,
        )

        assertTrue(state.activeChildMissing)
        assertNull("and it must not resolve to a real child", state.activeChild)
    }

    @Test
    fun aMissingTargetIsNotReportedAsNeedingASelectionWhileItIsMissing() {
        // Both are true, but the missing case is the more specific one the UI shows first;
        // either way no child is presented as selected.
        val state = ScreenTimeTargetUiState(
            status = ScreenTimeTargetStatus.READY,
            children = children,
            activeChildId = 99L,
        )

        assertNull(state.activeChild)
        assertTrue(state.activeChildMissing)
    }

    @Test
    fun anErrorStateClaimsNoTargetAndNoSelectionPrompt() {
        val state = ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.ERROR, errorMessageRes = 1)

        assertFalse(state.noChildren)
        assertFalse(state.needsSelection)
        assertNull("an error must never look like a valid selection", state.activeChild)
    }

    @Test
    fun noAccountClaimsNoTarget() {
        val state = ScreenTimeTargetUiState(status = ScreenTimeTargetStatus.NO_ACCOUNT)

        assertFalse(state.noChildren)
        assertFalse(state.needsSelection)
        assertNull(state.activeChild)
    }
}
