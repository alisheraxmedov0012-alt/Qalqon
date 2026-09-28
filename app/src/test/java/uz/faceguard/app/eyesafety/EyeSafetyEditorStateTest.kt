package uz.faceguard.app.eyesafety

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.feature.eyesafety.EYE_SAFETY_ACTIONS
import uz.faceguard.app.feature.eyesafety.EyeSafetyEditorState
import uz.faceguard.app.feature.eyesafety.EyeSafetyValidation
import uz.faceguard.app.feature.eyesafety.actionDescriptionRes
import uz.faceguard.app.feature.eyesafety.actionLabelRes
import uz.faceguard.app.feature.eyesafety.statusLabelRes

/**
 * Phase 6 Step 5 (pure JVM): the eye-safety editor state, its validation and its mapping to the
 * existing domain types, plus the presentation mappings. No Android, no database, no UI.
 */
class EyeSafetyEditorStateTest {

    private val accountId = 1L
    private val childId = 10L

    /**
     * A stored configuration. Exit thresholds default to a valid value *relative to* the enter
     * thresholds, so overriding one enter threshold in a test cannot accidentally build a
     * configuration the domain rejects.
     */
    private fun model(
        enabled: Boolean = true,
        warningEnter: Float = 0.30f,
        warningExit: Float? = null,
        dangerEnter: Float = 0.40f,
        dangerExit: Float? = null,
        confirmFrames: Int = 3,
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ): ChildEyeSafetyConfig {
        val resolvedWarningExit = warningExit ?: (warningEnter - 0.03f).coerceAtLeast(0f)
        val resolvedDangerExit = dangerExit ?: (dangerEnter - 0.05f).coerceAtLeast(resolvedWarningExit)
        return ChildEyeSafetyConfig(
            accountId = accountId,
            childId = childId,
            config = EyeSafetyConfig(
                enabled = enabled,
                warningEnterThreshold = warningEnter,
                warningExitThreshold = resolvedWarningExit,
                dangerEnterThreshold = dangerEnter,
                dangerExitThreshold = resolvedDangerExit,
                confirmFrames = confirmFrames,
            ),
            warningAction = warningAction,
            dangerAction = dangerAction,
            updatedAt = 1_000L,
        )
    }

    // ---- defaults come from the domain --------------------------------------

    @Test
    fun aNewEditorStartsFromTheCanonicalDomainDefaults() {
        val state = EyeSafetyEditorState.create()

        assertFalse("eye safety must not be pre-enabled", state.enabled)
        // Derived from the canonical domain constants, so the editor cannot drift from them.
        assertEquals(percentOf(EyeSafetyConfig.DEFAULT_WARNING_ENTER_THRESHOLD), state.warningEnterPercent)
        assertEquals(percentOf(EyeSafetyConfig.DEFAULT_WARNING_EXIT_THRESHOLD), state.warningExitPercent)
        assertEquals(percentOf(EyeSafetyConfig.DEFAULT_DANGER_ENTER_THRESHOLD), state.dangerEnterPercent)
        assertEquals(percentOf(EyeSafetyConfig.DEFAULT_DANGER_EXIT_THRESHOLD), state.dangerExitPercent)
        assertEquals(EyeSafetyConfig.DEFAULT_CONFIRM_FRAMES, state.confirmFrames)
        // ALLOW is the policy layer's documented no-op default for these two settings.
        assertEquals(ProtectionAction.ALLOW, state.warningAction)
        assertEquals(ProtectionAction.ALLOW, state.dangerAction)
        assertTrue("the defaults are a valid configuration", state.validate() is EyeSafetyValidation.Valid)
    }

    /** The whole percent a domain ratio represents, computed the way the editor does. */
    private fun percentOf(ratio: Float): Int = (ratio * 100f).roundToInt()

    // ---- B/C. loading a stored configuration --------------------------------

    @Test
    fun b_aStoredEnabledConfigurationLoadsEveryValue() {
        val state = EyeSafetyEditorState.from(
            model(warningAction = ProtectionAction.MUTE, dangerAction = ProtectionAction.HARD_BLOCK),
        )

        assertTrue(state.enabled)
        assertEquals("30", state.warningEnterPercentText)
        assertEquals("27", state.warningExitPercentText)
        assertEquals("40", state.dangerEnterPercentText)
        assertEquals("35", state.dangerExitPercentText)
        assertEquals("3", state.confirmFramesText)
        assertEquals(ProtectionAction.MUTE, state.warningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, state.dangerAction)
    }

    @Test
    fun c_aStoredDisabledConfigurationKeepsItsValues() {
        val state = EyeSafetyEditorState.from(model(enabled = false, warningEnter = 0.25f, dangerEnter = 0.55f))

        assertFalse(state.enabled)
        assertEquals("25", state.warningEnterPercentText)
        assertEquals("55", state.dangerEnterPercentText)
    }

    @Test
    fun aStoredConfigurationSurvivesTheRoundTrip() {
        val original = model(
            enabled = false,
            warningEnter = 0.22f,
            warningExit = 0.18f,
            dangerEnter = 0.61f,
            dangerExit = 0.5f,
            confirmFrames = 5,
            warningAction = ProtectionAction.MUTE,
            dangerAction = ProtectionAction.HARD_BLOCK,
        )

        val restored = EyeSafetyEditorState.from(original).toModel(accountId, childId, 9_999L)!!

        assertEquals(original.config, restored.config)
        assertEquals(original.warningAction, restored.warningAction)
        assertEquals(original.dangerAction, restored.dangerAction)
        assertEquals(9_999L, restored.updatedAt)
    }

    // ---- D. editing thresholds ----------------------------------------------

    @Test
    fun d_editingAThresholdUpdatesTheState() {
        val state = EyeSafetyEditorState.create().copy(warningEnterPercentText = "35")

        assertEquals(35, state.warningEnterPercent)
    }

    @Test
    fun d_thresholdsAreComparedInTheRightOrder() {
        // The domain invariant is warning strictly below danger; equal must be rejected too.
        val valid = EyeSafetyEditorState.create().copy(
            warningEnterPercentText = "30",
            dangerEnterPercentText = "31",
            warningExitPercentText = "27",
            dangerExitPercentText = "31",
        )
        assertTrue(valid.validate() is EyeSafetyValidation.Valid)

        val equal = valid.copy(dangerEnterPercentText = "30")
        assertEquals(
            EyeSafetyValidation.Invalid(R.string.eye_safety_error_threshold_order),
            equal.validate(),
        )

        val inverted = valid.copy(warningEnterPercentText = "45", dangerEnterPercentText = "40")
        assertEquals(
            EyeSafetyValidation.Invalid(R.string.eye_safety_error_threshold_order),
            inverted.validate(),
        )
    }

    // ---- E. editing actions --------------------------------------------------

    @Test
    fun e_editingAnActionUpdatesOnlyThatLevel() {
        val state = EyeSafetyEditorState.create()
            .copy(warningAction = ProtectionAction.MUTE, dangerAction = ProtectionAction.HARD_BLOCK)

        assertEquals(ProtectionAction.MUTE, state.warningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, state.dangerAction)
    }

    @Test
    fun e_bothLevelsAreStoredIndependently() {
        val modeled = EyeSafetyEditorState.create()
            .copy(
                enabled = true,
                warningAction = ProtectionAction.WARNING,
                dangerAction = ProtectionAction.HARD_BLOCK,
                warningEnterPercentText = "30",
                warningExitPercentText = "27",
                dangerEnterPercentText = "40",
                dangerExitPercentText = "35",
            )
            .toModel(accountId, childId, 1L)!!

        assertEquals(ProtectionAction.WARNING, modeled.warningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, modeled.dangerAction)
    }

    @Test
    fun e_allowIsAValidChoiceForEitherLevelAndIsNeverReplaced() {
        // Section 16: ALLOW means "no restriction for this level" and must survive as-is.
        val modeled = EyeSafetyEditorState.create()
            .copy(enabled = true, warningAction = ProtectionAction.ALLOW, dangerAction = ProtectionAction.ALLOW)
            .toModel(accountId, childId, 1L)!!

        assertEquals(ProtectionAction.ALLOW, modeled.warningAction)
        assertEquals(ProtectionAction.ALLOW, modeled.dangerAction)
    }

    @Test
    fun e_thePickerOffersOnlyImplementedActions() {
        assertEquals(
            listOf(
                ProtectionAction.ALLOW,
                ProtectionAction.WARNING,
                ProtectionAction.SOFT_BLOCK,
                ProtectionAction.HARD_BLOCK,
                ProtectionAction.MUTE,
            ),
            EYE_SAFETY_ACTIONS,
        )
        assertFalse(ProtectionAction.DIM in EYE_SAFETY_ACTIONS)
        assertFalse(ProtectionAction.BLUR in EYE_SAFETY_ACTIONS)
        assertFalse(ProtectionAction.BLACK_SCREEN in EYE_SAFETY_ACTIONS)
    }

    // ---- F. confirmation frames ---------------------------------------------

    @Test
    fun f_aPositiveConfirmationCountIsAccepted() {
        val state = EyeSafetyEditorState.create().copy(confirmFramesText = "7")

        assertEquals(7, state.confirmFrames)
        assertTrue(state.validate() is EyeSafetyValidation.Valid)
    }

    @Test
    fun f_zeroConfirmationFramesIsRejected() {
        val state = EyeSafetyEditorState.create().copy(confirmFramesText = "0")

        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_confirm_frames), state.validate())
        assertNull(state.toModel(accountId, childId, 1L))
    }

    @Test
    fun f_anEmptyConfirmationCountIsRejected() {
        val state = EyeSafetyEditorState.create().copy(confirmFramesText = "")

        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_invalid), state.validate())
    }

    // ---- range and parse validation ----------------------------------------

    @Test
    fun anEmptyThresholdIsRejectedRatherThanTreatedAsZero() {
        val state = EyeSafetyEditorState.create().copy(warningEnterPercentText = "")

        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_invalid), state.validate())
        assertNull(state.toModel(accountId, childId, 1L))
    }

    @Test
    fun enterThresholdsMustBeInsideOneToNinetyNine() {
        val zero = EyeSafetyEditorState.create().copy(warningEnterPercentText = "0")
        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_enter_range), zero.validate())

        val hundred = EyeSafetyEditorState.create().copy(dangerEnterPercentText = "100")
        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_enter_range), hundred.validate())
    }

    @Test
    fun exitThresholdsMayBeZeroButNotOneHundred() {
        val zeroExit = EyeSafetyEditorState.create().copy(
            warningExitPercentText = "0",
            dangerExitPercentText = "0",
        )
        assertTrue(zeroExit.validate() is EyeSafetyValidation.Valid)
        assertEquals(0, zeroExit.warningExitPercent)

        val hundred = EyeSafetyEditorState.create().copy(warningExitPercentText = "100")
        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_exit_range), hundred.validate())
    }

    @Test
    fun anExitAboveItsOwnEnterIsRejected() {
        val state = EyeSafetyEditorState.create().copy(
            warningEnterPercentText = "30",
            warningExitPercentText = "35",
            dangerEnterPercentText = "40",
            dangerExitPercentText = "35",
        )

        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_exit_order), state.validate())
    }

    @Test
    fun aDangerExitBelowTheWarningExitIsRejected() {
        val state = EyeSafetyEditorState.create().copy(
            warningEnterPercentText = "30",
            warningExitPercentText = "25",
            dangerEnterPercentText = "40",
            dangerExitPercentText = "20",
        )

        assertEquals(EyeSafetyValidation.Invalid(R.string.eye_safety_error_exit_order), state.validate())
    }

    @Test
    fun anEqualExitAndEnterIsAllowedBecauseTheDomainAllowsIt() {
        val state = EyeSafetyEditorState.create().copy(
            warningEnterPercentText = "30",
            warningExitPercentText = "30",
            dangerEnterPercentText = "40",
            dangerExitPercentText = "30",
        )

        assertTrue(state.validate() is EyeSafetyValidation.Valid)
    }

    @Test
    fun anInvalidStateYieldsNoModel() {
        val state = EyeSafetyEditorState.create().copy(warningEnterPercentText = "abc")

        assertNull(state.toModel(accountId, childId, 1L))
    }

    @Test
    fun theModelCarriesTheScopeItWasGiven() {
        val state = EyeSafetyEditorState.create().copy(enabled = true, warningEnterPercentText = "30")

        val modeled = state.toModel(accountId = 7L, childId = 42L, updatedAt = 5L)!!

        assertEquals(7L, modeled.accountId)
        assertEquals(42L, modeled.childId)
        assertEquals(5L, modeled.updatedAt)
    }

    // ---- K. presentation mappings all resolve from resources ----------------

    @Test
    fun k_everyOfferedActionHasALabelAndADescription() {
        EYE_SAFETY_ACTIONS.forEach { action ->
            assertTrue("action $action needs a label", actionLabelRes(action) != 0)
            assertTrue("action $action needs a description", actionDescriptionRes(action) != 0)
        }
    }

    @Test
    fun k_theDomainOnlyActionsStillResolveRatherThanFallingThrough() {
        listOf(ProtectionAction.DIM, ProtectionAction.BLUR, ProtectionAction.BLACK_SCREEN).forEach { action ->
            assertEquals(R.string.eye_safety_action_unavailable_hint, actionDescriptionRes(action))
        }
    }

    @Test
    fun k_statusDistinguishesUnconfiguredFromDisabled() {
        assertEquals(R.string.eye_safety_status_not_configured, statusLabelRes(configured = false, enabled = false))
        assertEquals(R.string.eye_safety_status_not_configured, statusLabelRes(configured = false, enabled = true))
        assertEquals(R.string.eye_safety_status_enabled, statusLabelRes(configured = true, enabled = true))
        assertEquals(R.string.eye_safety_status_disabled, statusLabelRes(configured = true, enabled = false))
    }
}
