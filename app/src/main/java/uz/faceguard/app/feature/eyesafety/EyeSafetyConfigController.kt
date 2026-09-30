package uz.faceguard.app.feature.eyesafety

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 5: everything the eye-safety configuration screen shows and does.
 *
 * It is deliberately Android-free: it depends only on the domain [EyeSafetyRepository], a clock and
 * a [CoroutineScope]. That is what lets the whole configuration surface — load, unconfigured state,
 * editing, validation, save and both failure paths — be tested on the JVM against production code,
 * with a fake repository standing in for Room.
 *
 * It is scoped to exactly one account + child, fixed at construction, so a save can only ever write
 * the scope it was built for: a stale instance can never write to another child. It never touches
 * Room, the DAO, the protection runtime or the policy layer — the repository is its only port.
 */
data class EyeSafetyUiState(
    /** True until the stored configuration has been read, so defaults are never shown as if saved. */
    val loading: Boolean = true,
    /**
     * True once a row exists for this child. `false` means **unconfigured** — the child has no
     * eye-safety configuration at all, which is not the same as a stored `enabled = false`.
     */
    val configured: Boolean = false,
    val editor: EyeSafetyEditorState = EyeSafetyEditorState.create(),
    /** Set when the stored configuration could not be read; the form is then not trustworthy. */
    val loadErrorMessageRes: Int? = null,
)

class EyeSafetyConfigController(
    private val repository: EyeSafetyRepository,
    private val accountId: Long,
    private val childId: Long,
    private val clock: () -> Long,
    scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(EyeSafetyUiState())
    val state: StateFlow<EyeSafetyUiState> = _state

    init {
        // A one-shot read, not a live collector: this is a configuration *form*, and a live stream
        // would clobber values the parent is still editing. Nothing is written here — simply opening
        // the screen must not create a row, so an unconfigured child stays unconfigured until the
        // parent explicitly saves. The runtime keeps its own `observeConfig` collector (Phase 6
        // Step 4) for enforcement, which a save reaches without any extra wiring.
        scope.launch { load() }
    }

    private suspend fun load() {
        runCatching { repository.config(accountId, childId) }.fold(
            onSuccess = { stored ->
                _state.update {
                    it.copy(
                        loading = false,
                        configured = stored != null,
                        // An unconfigured child starts from the canonical domain defaults, and the
                        // form is disabled until the parent enables it. No row is created.
                        editor = stored?.let(EyeSafetyEditorState::from) ?: EyeSafetyEditorState.create(),
                        loadErrorMessageRes = null,
                    )
                }
            },
            onFailure = {
                // A read failure is reported rather than shown as "unconfigured", which would look
                // like the parent's settings had been lost.
                _state.update {
                    it.copy(loading = false, loadErrorMessageRes = R.string.eye_safety_error_load)
                }
            },
        )
    }

    // ---- editing ------------------------------------------------------------

    /** Applies an edit to the form, unless a save is in flight. */
    private fun edit(transform: (EyeSafetyEditorState) -> EyeSafetyEditorState) {
        _state.update { current ->
            if (current.editor.busy) current else current.copy(editor = transform(current.editor))
        }
    }

    fun onEnabledChange(enabled: Boolean) = edit { it.copy(enabled = enabled) }

    // Thresholds are whole percents. Only digits can be typed, so a decimal, sign or separator can
    // never enter the field; the range is checked by validation rather than silently truncated, so
    // an out-of-range entry is reported instead of being "fixed".
    fun onWarningEnterChange(value: String) =
        edit { it.copy(warningEnterPercentText = digits(value), errorMessageRes = null) }

    fun onWarningExitChange(value: String) =
        edit { it.copy(warningExitPercentText = digits(value), errorMessageRes = null) }

    fun onDangerEnterChange(value: String) =
        edit { it.copy(dangerEnterPercentText = digits(value), errorMessageRes = null) }

    fun onDangerExitChange(value: String) =
        edit { it.copy(dangerExitPercentText = digits(value), errorMessageRes = null) }

    fun onConfirmFramesChange(value: String) =
        edit { it.copy(confirmFramesText = digits(value), errorMessageRes = null) }

    fun onWarningActionChange(action: ProtectionAction) = edit { it.copy(warningAction = action) }

    fun onDangerActionChange(action: ProtectionAction) = edit { it.copy(dangerAction = action) }

    fun onErrorShown() = edit { it.copy(errorMessageRes = null) }

    // ---- save ---------------------------------------------------------------

    /**
     * Validates and persists the form.
     *
     * @return true when the write succeeded. An invalid form writes nothing and reports the reason;
     *   a repository failure keeps the parent's input so they can retry, and never reports success.
     */
    suspend fun save(): Boolean {
        val current = _state.value
        if (current.editor.busy) return false

        val model = current.editor.toModel(accountId, childId, clock())
        if (model == null) {
            val message = (current.editor.validate() as? EyeSafetyValidation.Invalid)?.messageRes
            _state.update { it.copy(editor = it.editor.copy(errorMessageRes = message)) }
            return false
        }

        _state.update { it.copy(editor = it.editor.copy(saving = true, errorMessageRes = null)) }
        val failure = runCatching { repository.save(model) }.exceptionOrNull()

        return if (failure == null) {
            // The form now mirrors exactly what is stored, and the child is configured.
            _state.update { it.copy(configured = true, editor = EyeSafetyEditorState.from(model)) }
            true
        } else {
            _state.update { it.copy(editor = it.editor.copy(saving = false, errorMessageRes = R.string.eye_safety_error_save)) }
            false
        }
    }

    private fun digits(value: String): String =
        value.filter(Char::isDigit).take(MAX_DIGITS)

    private companion object {
        /** Three digits admit any percent a parent could type, including the out-of-range 100. */
        const val MAX_DIGITS = 3
    }
}
