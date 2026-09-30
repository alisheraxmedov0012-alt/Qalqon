package uz.faceguard.app.feature.schedule

import java.time.DayOfWeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRepository
import uz.faceguard.app.domain.schedule.ScheduleRule

/** One schedule as the list shows it: the rule plus how many apps it targets. */
data class ScheduleListRow(
    val schedule: ScheduleRule,
    val targetCount: Int,
)

/** The per-child schedule list, including the "not loaded yet" state. */
data class ScheduleListState(
    /** True until the first emission, so an empty list is never flashed before data arrives. */
    val loading: Boolean = true,
    val rows: List<ScheduleListRow> = emptyList(),
    val errorMessageRes: Int? = null,
    /** The schedule awaiting delete confirmation; deletion only happens once this is confirmed. */
    val pendingDelete: ScheduleRule? = null,
    /** True while a delete or toggle write is in flight, so it cannot be submitted twice. */
    val busy: Boolean = false,
) {
    fun rowFor(scheduleId: Long): ScheduleListRow? = rows.firstOrNull { it.schedule.id == scheduleId }
}

/** The parent's protected-app catalog, as the schedule app selector sees it. */
data class ScheduleCatalogState(
    val loaded: Boolean = false,
    val apps: List<ProtectedApp> = emptyList(),
)

/**
 * Phase 5 Step 5: the per-child schedule configuration logic.
 *
 * It is deliberately Android-free: it depends only on the domain [ScheduleRepository], the
 * protected-app catalog as a plain [Flow], and a [CoroutineScope]. That is what lets the whole
 * configuration surface — list, editor, save, delete, toggle, validation and failure handling — be
 * tested on the JVM against production code, with a fake repository standing in for Room.
 *
 * It is scoped to exactly one account + child, so a schedule of another child can never be listed,
 * edited or deleted from this instance. It never touches Room, never resolves a schedule and never
 * enforces anything: the existing repository and the existing policy pipeline own those.
 */
class ScheduleConfigController(
    private val repository: ScheduleRepository,
    private val accountId: Long,
    private val childId: Long,
    protectedApps: Flow<List<ProtectedApp>>,
    scope: CoroutineScope,
) {

    private val _editor = MutableStateFlow(ScheduleEditorState.create())
    val editor: StateFlow<ScheduleEditorState> = _editor

    private val _list = MutableStateFlow(ScheduleListState())
    val list: StateFlow<ScheduleListState> = _list

    /**
     * The catalog the selector may offer: only apps the parent has already protected. An app that
     * is not protected cannot be introduced through this screen.
     */
    val catalog: StateFlow<ScheduleCatalogState> = protectedApps
        .map { apps -> apps.filter { it.isProtected } }
        .map { ScheduleCatalogState(loaded = true, apps = it) }
        .catch { emit(ScheduleCatalogState(loaded = true, apps = emptyList())) }
        .stateIn(scope, SharingStarted.Eagerly, ScheduleCatalogState())

    init {
        // One flow per account + child: the list can never show another child's schedules. Targets
        // are observed per schedule so the affected-app count stays correct as it changes.
        scope.launch {
            repository.observeSchedules(accountId, childId)
                .flatMapLatest { rules ->
                    if (rules.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            rules.map { rule ->
                                repository.observeTargetPackages(accountId, childId, rule.id)
                                    .map { packages -> rule to packages.size }
                            },
                        ) { counts -> counts.map { (rule, count) -> ScheduleListRow(rule, count) } }
                    }
                }
                // Widened to nullable so a read failure can be distinguished from an empty list.
                .map { rows -> rows as List<ScheduleListRow>? }
                .catch { emit(null) }
                .collect { rows ->
                    _list.update { current ->
                        if (rows == null) {
                            // A read failure is reported; the last known rows are not cleared, so a
                            // transient failure cannot look like "the schedules were deleted".
                            current.copy(loading = false, errorMessageRes = R.string.schedule_error_load)
                        } else {
                            // Drop a pending confirmation whose schedule no longer exists.
                            val stillPresent = current.pendingDelete
                                ?.takeIf { pending -> rows.any { it.schedule.id == pending.id } }
                            current.copy(loading = false, rows = rows, pendingDelete = stillPresent)
                        }
                    }
                }
        }
    }

    // ---- editor lifecycle ---------------------------------------------------

    /** Opens the editor for a new schedule, discarding any previous editing session. */
    suspend fun startAdd() {
        _editor.value = ScheduleEditorState.create()
    }

    /**
     * Opens the editor for [rule], loading its affected apps through the repository.
     *
     * The rule must belong to this account + child; a rule from another scope has no targets here
     * and is not silently adopted.
     */
    suspend fun startEdit(rule: ScheduleRule) {
        val targets = runCatching { repository.targetPackages(accountId, childId, rule.id) }
            .getOrDefault(emptyList())
        _editor.value = ScheduleEditorState.from(rule, targets.toSet())
    }

    /** Discards the editing session. Nothing is written to the repository. */
    suspend fun cancelEdit() {
        _editor.value = ScheduleEditorState.create()
    }

    fun edit(transform: (ScheduleEditorState) -> ScheduleEditorState) {
        _editor.update { current -> if (current.busy) current else transform(current) }
    }

    fun onNameChange(value: String) = edit { it.copy(name = value, errorMessageRes = null) }

    fun onModeChange(value: ScheduleMode) = edit { it.copy(mode = value) }

    /** Changing the action never touches mode, days, time, priority or apps. */
    fun onActionChange(value: ProtectionAction) = edit { it.copy(action = value) }

    fun onPriorityChange(value: String) =
        // Only digits and an optional leading sign can be typed, so a decimal can never be entered.
        edit { it.copy(priorityText = value.filter { char -> char.isDigit() || char == '-' }.take(MAX_PRIORITY_DIGITS), errorMessageRes = null) }

    fun onToggleDay(day: DayOfWeek) = edit { it.withDayToggled(day) }

    fun onSetDays(days: Set<DayOfWeek>) = edit { it.copy(days = days) }

    fun onStartMinuteChange(minuteOfDay: Int) = edit { it.copy(startMinuteOfDay = minuteOfDay, errorMessageRes = null) }

    fun onEndMinuteChange(minuteOfDay: Int) = edit { it.copy(endMinuteOfDay = minuteOfDay, errorMessageRes = null) }

    fun onTogglePackage(packageName: String) = edit { current ->
        val selected = if (packageName in current.selectedPackages) {
            current.selectedPackages - packageName
        } else {
            current.selectedPackages + packageName
        }
        current.copy(selectedPackages = selected)
    }

    fun onEnabledChange(enabled: Boolean) = edit { it.copy(enabled = enabled) }

    fun onErrorShown() = edit { it.copy(errorMessageRes = null) }

    /**
     * Validates and persists the editor's schedule together with its affected apps.
     *
     * @return true when the write succeeded. On [ScheduleEditorValidation.Invalid] nothing is
     *   written and the editor reports the reason; on a repository failure the editor keeps the
     *   parent's input so they can retry.
     */
    suspend fun saveEditor(): Boolean {
        val current = _editor.value
        if (current.busy) return false

        val draft = current.toDraft()
        if (draft == null) {
            val validation = current.validate()
            val message = (validation as? ScheduleEditorValidation.Invalid)?.messageRes
            _editor.update { it.copy(errorMessageRes = message) }
            return false
        }

        _editor.update { it.copy(saving = true, errorMessageRes = null) }
        val result = runCatching {
            val scheduleId = current.scheduleId
            if (scheduleId == null) {
                repository.create(accountId, childId, draft, current.targetPackages())
            } else {
                // Same id, same account and child: an edit updates in place and never duplicates.
                val stored = repository.update(
                    accountId = accountId,
                    childId = childId,
                    schedule = draft.withId(scheduleId),
                    targetPackages = current.targetPackages(),
                )
                stored ?: error("schedule $scheduleId does not belong to this account and child")
            }
        }
        return result.fold(
            onSuccess = { saved ->
                _editor.update { it.copy(saving = false, scheduleId = saved.id, errorMessageRes = null) }
                true
            },
            onFailure = {
                _editor.update { it.copy(saving = false, errorMessageRes = R.string.schedule_error_save) }
                false
            },
        )
    }

    // ---- delete (with explicit confirmation) --------------------------------

    /** Asks for confirmation; nothing is deleted until [confirmDelete]. */
    fun requestDelete(rule: ScheduleRule) {
        _list.update { it.copy(pendingDelete = rule) }
    }

    /** Cancels the confirmation. The schedule is untouched. */
    fun cancelDelete() {
        _list.update { it.copy(pendingDelete = null) }
    }

    /**
     * Deletes the schedule awaiting confirmation. The existing repository removes the schedule and
     * its target memberships transactionally, so no orphan rows are left behind.
     *
     * @return true when the delete was submitted. On failure the list keeps the row (it is
     *   derived from the repository, so nothing is falsely removed) and reports the error.
     */
    suspend fun confirmDelete(): Boolean {
        val pending = _list.value.pendingDelete ?: return false
        if (_list.value.busy) return false

        _list.update { it.copy(busy = true, errorMessageRes = null) }
        val failure = runCatching { repository.delete(accountId, childId, pending.id) }.exceptionOrNull()
        _list.update { current ->
            current.copy(
                busy = false,
                pendingDelete = null,
                errorMessageRes = if (failure == null) null else R.string.schedule_error_delete,
            )
        }
        return failure == null
    }

    /**
     * Persists the enabled flag. The schedule is never deleted by disabling it, and its affected
     * apps are carried over rather than wiped, because the repository's update writes both.
     */
    suspend fun setEnabled(rule: ScheduleRule, enabled: Boolean): Boolean {
        if (_list.value.busy) return false
        _list.update { it.copy(busy = true, errorMessageRes = null) }
        val failure = runCatching {
            val targets = repository.targetPackages(accountId, childId, rule.id).toSet()
            repository.update(accountId, childId, rule.copy(enabled = enabled), targets)
                ?: error("schedule ${rule.id} does not belong to this account and child")
        }.exceptionOrNull()
        _list.update { current ->
            current.copy(
                busy = false,
                errorMessageRes = if (failure == null) null else R.string.schedule_error_update,
            )
        }
        return failure == null
    }

    private companion object {
        /** Four digits plus a sign covers any realistic priority without letting text run away. */
        const val MAX_PRIORITY_DIGITS = 5
    }
}
