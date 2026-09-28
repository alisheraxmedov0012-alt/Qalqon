package uz.faceguard.app.feature.eyesafety

import kotlin.math.roundToInt
import uz.faceguard.app.R
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 5: the result of validating an editor state.
 *
 * A sealed result rather than a boolean, so the screen can show a specific localized message and
 * tests can assert the exact reason. Mirrors the project's existing `ScheduleEditorValidation`.
 */
sealed interface EyeSafetyValidation {
    data object Valid : EyeSafetyValidation
    data class Invalid(val messageRes: Int) : EyeSafetyValidation
}

/**
 * Phase 6 Step 5: the eye-safety editor's state — a *UI state wrapper*, not a second configuration
 * model.
 *
 * The persisted shape is still the existing [ChildEyeSafetyConfig] / [EyeSafetyConfig]; this type
 * only holds what the parent is currently editing, in the form the fields need. Thresholds and the
 * confirmation count are held as raw **text** so in-progress input is representable (and can be
 * reported as invalid) instead of being coerced while typing — the same approach the schedule
 * editor takes with its priority field.
 *
 * Percentages, not ratios: the persisted representation is an integer percent (`30` = 30%), and the
 * parent configures whole percents, so no float ever reaches the UI. Conversion happens only when a
 * model is built.
 *
 * Every edited field is validated against the **existing domain invariants** — this does not define
 * a second set of rules. [toModel] constructs a real [EyeSafetyConfig], so the domain constructor
 * remains the final authority even if this validation were ever to allow something through.
 */
data class EyeSafetyEditorState(
    val enabled: Boolean = false,
    val warningEnterPercentText: String,
    val warningExitPercentText: String,
    val dangerEnterPercentText: String,
    val dangerExitPercentText: String,
    val confirmFramesText: String,
    val warningAction: ProtectionAction,
    val dangerAction: ProtectionAction,
    val saving: Boolean = false,
    val errorMessageRes: Int? = null,
) {

    /** True while a save is in flight, so the UI can prevent a duplicate submission. */
    val busy: Boolean get() = saving

    val warningEnterPercent: Int? get() = warningEnterPercentText.trim().toIntOrNull()
    val warningExitPercent: Int? get() = warningExitPercentText.trim().toIntOrNull()
    val dangerEnterPercent: Int? get() = dangerEnterPercentText.trim().toIntOrNull()
    val dangerExitPercent: Int? get() = dangerExitPercentText.trim().toIntOrNull()
    val confirmFrames: Int? get() = confirmFramesText.trim().toIntOrNull()

    /**
     * Validates against the domain's own invariants, in the order a parent would fix them.
     *
     * The rules mirror [EyeSafetyConfig]'s `init` exactly — an enter threshold is a ratio strictly
     * inside `(0, 1)`, an exit threshold is inside `[0, 1]`, warning stays below danger, an exit
     * never exceeds its own enter, and `confirmFrames` is positive. Nothing is clamped or swapped;
     * invalid input is reported so it can be corrected.
     */
    fun validate(): EyeSafetyValidation {
        val warningEnter = warningEnterPercent
            ?: return invalid(R.string.eye_safety_error_invalid)
        val warningExit = warningExitPercent
            ?: return invalid(R.string.eye_safety_error_invalid)
        val dangerEnter = dangerEnterPercent
            ?: return invalid(R.string.eye_safety_error_invalid)
        val dangerExit = dangerExitPercent
            ?: return invalid(R.string.eye_safety_error_invalid)
        val frames = confirmFrames
            ?: return invalid(R.string.eye_safety_error_invalid)

        // Enter thresholds are ratios strictly between 0 and 1, so 0 and 100 are not usable.
        if (warningEnter !in ENTER_PERCENT_RANGE || dangerEnter !in ENTER_PERCENT_RANGE) {
            return invalid(R.string.eye_safety_error_enter_range)
        }
        // Exit thresholds may be 0 (no lower dead band) but still cannot be 100.
        if (warningExit !in EXIT_PERCENT_RANGE || dangerExit !in EXIT_PERCENT_RANGE) {
            return invalid(R.string.eye_safety_error_exit_range)
        }
        if (warningEnter >= dangerEnter) {
            return invalid(R.string.eye_safety_error_threshold_order)
        }
        if (warningExit > warningEnter || dangerExit > dangerEnter || dangerExit < warningExit) {
            return invalid(R.string.eye_safety_error_exit_order)
        }
        if (frames <= 0) {
            return invalid(R.string.eye_safety_error_confirm_frames)
        }
        return EyeSafetyValidation.Valid
    }

    /**
     * The model to persist, or `null` when the input is invalid.
     *
     * Built through the existing domain types, so the [EyeSafetyConfig] invariants — and the scope
     * checks in [ChildEyeSafetyConfig] — are enforced by the domain rather than by a copy of them.
     * `updatedAt` is supplied by the caller; no clock is read here.
     */
    fun toModel(accountId: Long, childId: Long, updatedAt: Long): ChildEyeSafetyConfig? {
        if (validate() !is EyeSafetyValidation.Valid) return null
        val warningEnter = warningEnterPercent ?: return null
        val warningExit = warningExitPercent ?: return null
        val dangerEnter = dangerEnterPercent ?: return null
        val dangerExit = dangerExitPercent ?: return null
        val frames = confirmFrames ?: return null

        return runCatching {
            ChildEyeSafetyConfig(
                accountId = accountId,
                childId = childId,
                config = EyeSafetyConfig(
                    enabled = enabled,
                    warningEnterThreshold = warningEnter.toRatio(),
                    warningExitThreshold = warningExit.toRatio(),
                    dangerEnterThreshold = dangerEnter.toRatio(),
                    dangerExitThreshold = dangerExit.toRatio(),
                    confirmFrames = frames,
                ),
                warningAction = warningAction,
                dangerAction = dangerAction,
                updatedAt = updatedAt,
            )
        }.getOrNull()
    }

    private fun invalid(messageRes: Int): EyeSafetyValidation = EyeSafetyValidation.Invalid(messageRes)

    companion object {
        /** An enter threshold is a ratio strictly inside `(0, 1)`, expressed as whole percents. */
        val ENTER_PERCENT_RANGE = 1..99

        /** An exit threshold is a ratio inside `[0, 1)`, expressed as whole percents. */
        val EXIT_PERCENT_RANGE = 0..99

        /**
         * The starting point for a child with no stored configuration: the canonical
         * [EyeSafetyConfig.DEFAULT] thresholds and confirmation count, **disabled**, with both
         * actions left at [ProtectionAction.ALLOW].
         *
         * The enabled flag is forced off even though `EyeSafetyConfig.DEFAULT` has it on: an
         * unconfigured child must never open with eye safety apparently switched on, because no row
         * exists and nothing would enforce it. Only an explicit save by the parent can enable it.
         *
         * ALLOW is the same no-op default the policy layer already documents for these two settings
         * (`PolicySettings.eyeSafetyWarningAction` / `eyeSafetyDangerAction`), so opening or saving
         * this screen can never invent a restriction the parent did not choose.
         */
        fun create(): EyeSafetyEditorState = from(
            config = EyeSafetyConfig.DEFAULT.copy(enabled = false),
            warningAction = ProtectionAction.ALLOW,
            dangerAction = ProtectionAction.ALLOW,
        )

        /** Loads a stored configuration into the editor, preserving every value. */
        fun from(model: ChildEyeSafetyConfig): EyeSafetyEditorState = from(
            config = model.config,
            warningAction = model.warningAction,
            dangerAction = model.dangerAction,
        )

        private fun from(
            config: EyeSafetyConfig,
            warningAction: ProtectionAction,
            dangerAction: ProtectionAction,
        ) = EyeSafetyEditorState(
            enabled = config.enabled,
            warningEnterPercentText = config.warningEnterThreshold.toPercentText(),
            warningExitPercentText = config.warningExitThreshold.toPercentText(),
            dangerEnterPercentText = config.dangerEnterThreshold.toPercentText(),
            dangerExitPercentText = config.dangerExitThreshold.toPercentText(),
            confirmFramesText = config.confirmFrames.toString(),
            warningAction = warningAction,
            dangerAction = dangerAction,
        )
    }
}

/** `0.30f` -> `"30"`. Rounded so a stored ratio always lands on the percent it represents. */
private fun Float.toPercentText(): String = (this * 100f).roundToInt().toString()

/** `30` -> `0.30f`, the normalized ratio the domain stores. */
private fun Int.toRatio(): Float = this / 100f
