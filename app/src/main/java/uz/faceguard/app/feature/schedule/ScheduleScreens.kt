package uz.faceguard.app.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.AppLoadingButton
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRepository
import uz.faceguard.app.domain.schedule.ScheduleRule

/** Navigation contract for the per-child schedule routes. */
object ScheduleArgs {
    const val CHILD_ID = "childId"
    const val SCHEDULE_ID = "scheduleId"

    /** `-1` means "creating a new schedule" — the same convention the requests route uses. */
    const val NEW_SCHEDULE_ID = -1L
}

/**
 * Phase 5 Step 5: the per-child schedule list.
 *
 * The child comes from the route, so the screen is always scoped to one child; the controller is
 * built with that account + child and can therefore never observe another child's schedules.
 */
@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    accountRepository: AccountRepository,
    scheduleRepository: ScheduleRepository,
    protectedAppsRepository: ProtectedAppsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val childId: Long = savedStateHandle.get<Long>(ScheduleArgs.CHILD_ID) ?: -1L

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _list = MutableStateFlow(ScheduleListState())
    val list: StateFlow<ScheduleListState> = _list.asStateFlow()

    private val controller = MutableStateFlow<ScheduleConfigController?>(null)

    init {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount()
            if (account == null || childId <= 0L) {
                _state.value = UiState.Error(R.string.error_invalid_credentials)
                return@launch
            }
            val created = ScheduleConfigController(
                repository = scheduleRepository,
                accountId = account.id,
                childId = childId,
                protectedApps = protectedAppsRepository.protectedApps,
                scope = viewModelScope,
            )
            controller.value = created
            _state.value = UiState.Success
            created.list.collect { _list.value = it }
        }
    }

    fun requestDelete(rule: ScheduleRule) = controller.value?.requestDelete(rule)

    fun cancelDelete() = controller.value?.cancelDelete()

    fun confirmDelete() {
        val target = controller.value ?: return
        viewModelScope.launch { target.confirmDelete() }
    }

    fun setEnabled(rule: ScheduleRule, enabled: Boolean) {
        val target = controller.value ?: return
        viewModelScope.launch { target.setEnabled(rule, enabled) }
    }
}

/**
 * Phase 5 Step 5: the schedule editor (create or edit).
 *
 * The route carries the child and an optional schedule id. Editing loads through the existing
 * [ScheduleRepository], so the editor shows exactly what is persisted and never a second copy of it.
 */
@HiltViewModel
class ScheduleEditorViewModel @Inject constructor(
    accountRepository: AccountRepository,
    scheduleRepository: ScheduleRepository,
    protectedAppsRepository: ProtectedAppsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val childId: Long = savedStateHandle.get<Long>(ScheduleArgs.CHILD_ID) ?: -1L
    private val scheduleId: Long = savedStateHandle.get<Long>(ScheduleArgs.SCHEDULE_ID) ?: ScheduleArgs.NEW_SCHEDULE_ID

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _editor = MutableStateFlow(ScheduleEditorState.create())
    val editor: StateFlow<ScheduleEditorState> = _editor.asStateFlow()

    private val _catalog = MutableStateFlow(ScheduleCatalogState())
    val catalog: StateFlow<ScheduleCatalogState> = _catalog.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val controller = MutableStateFlow<ScheduleConfigController?>(null)
    private val isNew: Boolean get() = scheduleId <= 0L

    init {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount()
            if (account == null || childId <= 0L) {
                _state.value = UiState.Error(R.string.error_invalid_credentials)
                return@launch
            }
            val created = ScheduleConfigController(
                repository = scheduleRepository,
                accountId = account.id,
                childId = childId,
                protectedApps = protectedAppsRepository.protectedApps,
                scope = viewModelScope,
            )
            controller.value = created
            _state.value = UiState.Success

            launch { created.catalog.collect { _catalog.value = it } }
            launch { created.editor.collect { _editor.value = it } }

            if (isNew) {
                created.startAdd()
            } else {
                val rule = runCatching { scheduleRepository.schedule(account.id, childId, scheduleId) }.getOrNull()
                if (rule == null) {
                    // Not this account's/child's schedule (or deleted meanwhile): never adopt it.
                    _state.value = UiState.Error(R.string.schedule_error_load)
                } else {
                    created.startEdit(rule)
                }
            }
        }
    }

    fun onNameChange(value: String) = controller.value?.onNameChange(value)
    fun onModeChange(value: ScheduleMode) = controller.value?.onModeChange(value)
    fun onActionChange(value: ProtectionAction) = controller.value?.onActionChange(value)
    fun onPriorityChange(value: String) = controller.value?.onPriorityChange(value)
    fun onToggleDay(value: DayOfWeek) = controller.value?.onToggleDay(value)
    fun onSetDays(value: Set<DayOfWeek>) = controller.value?.onSetDays(value)
    fun onStartMinuteChange(value: Int) = controller.value?.onStartMinuteChange(value)
    fun onEndMinuteChange(value: Int) = controller.value?.onEndMinuteChange(value)
    fun onTogglePackage(value: String) = controller.value?.onTogglePackage(value)
    fun onEnabledChange(value: Boolean) = controller.value?.onEnabledChange(value)
    fun onErrorShown() = controller.value?.onErrorShown()

    fun save() {
        val target = controller.value ?: return
        viewModelScope.launch { if (target.saveEditor()) _saved.value = true }
    }
}

/** Confirmation dialog shown before a schedule is deleted. */
@Composable
private fun DeleteScheduleDialog(rule: ScheduleRule, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_delete_title)) },
        text = { Text(stringResource(R.string.schedule_delete_message, rule.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.schedule_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(
    onBack: () -> Unit,
    onAddSchedule: () -> Unit,
    onEditSchedule: (Long) -> Unit,
    viewModel: ScheduleListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (val screen = state) {
            is UiState.Loading, is UiState.Idle -> StatusMessage(padding) {
                Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Error -> StatusMessage(padding) {
                Text(
                    stringResource(screen.messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is UiState.Success -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.schedule_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    AppLoadingButton(
                        labelRes = R.string.schedule_add,
                        loading = false,
                        onClick = onAddSchedule,
                    )
                }
                list.errorMessageRes?.let { messageRes ->
                    item {
                        Text(
                            stringResource(messageRes),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                when {
                    // Never flash "no schedules" before the first emission has arrived.
                    list.loading -> item {
                        Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)
                    }

                    list.rows.isEmpty() -> item {
                        SectionCard(title = stringResource(R.string.schedule_title)) {
                            Text(stringResource(R.string.schedule_no_schedules))
                        }
                    }

                    else -> items(list.rows, key = { it.schedule.id }) { row ->
                        ScheduleRowCard(
                            row = row,
                            busy = list.busy,
                            onToggleEnabled = { enabled -> viewModel.setEnabled(row.schedule, enabled) },
                            onEdit = { onEditSchedule(row.schedule.id) },
                            onDelete = { viewModel.requestDelete(row.schedule) },
                        )
                    }
                }
            }
        }
    }

    list.pendingDelete?.let { pending ->
        DeleteScheduleDialog(
            rule = pending,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun StatusMessage(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        content()
    }
}

@Composable
private fun ScheduleRowCard(
    row: ScheduleListRow,
    busy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val rule = row.schedule
    val dayLabels = SCHEDULE_DAYS_ORDER.filter { rule.days.contains(it) }.map { stringResource(dayLabelRes(it)) }

    SectionCard(title = rule.name) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (rule.enabled) R.string.schedule_enabled else R.string.schedule_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = if (rule.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Switch(checked = rule.enabled, onCheckedChange = onToggleEnabled, enabled = !busy)
        }
        Text(dayLabels.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        Text(
            "${formatWindow(rule)}" +
                if (rule.window.crossesMidnight) " (${stringResource(R.string.schedule_cross_midnight)})" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${stringResource(R.string.schedule_action)}: ${stringResource(actionLabelRes(rule.action))}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${stringResource(R.string.schedule_priority)}: ${rule.priority}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.schedule_apps_count, row.targetCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.btn_edit)) }
            OutlinedButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.btn_delete)) }
        }
    }
}

/** A whole-minute time picker dialog. Material 3's picker is hour + minute, so seconds cannot enter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    titleRes: Int,
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) },
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ScheduleEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var startPickerOpen by remember { mutableStateOf(false) }
    var endPickerOpen by remember { mutableStateOf(false) }

    // Leave the editor only after the write actually succeeded.
    LaunchedEffect(saved) { if (saved) onSaved() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (editor.isNew) R.string.schedule_add else R.string.schedule_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (val screen = state) {
            is UiState.Loading, is UiState.Idle -> StatusMessage(padding) {
                Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Error -> StatusMessage(padding) {
                Text(
                    stringResource(screen.messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is UiState.Success -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                editor.errorMessageRes?.let { messageRes ->
                    item {
                        Text(
                            stringResource(messageRes),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_title)) {
                        OutlinedTextField(
                            value = editor.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text(stringResource(R.string.schedule_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    SectionCard(
                        title = stringResource(R.string.schedule_mode),
                        subtitle = stringResource(modeDescriptionRes(editor.mode)),
                    ) {
                        // Mode is descriptive metadata: selecting one never changes the action.
                        ChipRow(
                            values = ScheduleMode.entries,
                            selected = editor.mode,
                            labelRes = ::modeLabelRes,
                            onSelect = viewModel::onModeChange,
                        )
                    }
                }
                item {
                    SectionCard(
                        title = stringResource(R.string.schedule_action),
                        subtitle = stringResource(actionDescriptionRes(editor.action)),
                    ) {
                        ChipRow(
                            values = SCHEDULE_ACTIONS,
                            selected = editor.action,
                            labelRes = ::actionLabelRes,
                            onSelect = viewModel::onActionChange,
                        )
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_days)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.onSetDays(DayPresets.ALL) }) {
                                Text(stringResource(R.string.schedule_days_all))
                            }
                            OutlinedButton(onClick = { viewModel.onSetDays(DayPresets.WEEKDAYS) }) {
                                Text(stringResource(R.string.schedule_days_weekdays))
                            }
                            OutlinedButton(onClick = { viewModel.onSetDays(DayPresets.WEEKENDS) }) {
                                Text(stringResource(R.string.schedule_days_weekends))
                            }
                        }
                        Column {
                            SCHEDULE_DAYS_ORDER.forEach { day ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.onToggleDay(day) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = day in editor.days,
                                        onCheckedChange = { viewModel.onToggleDay(day) },
                                    )
                                    Text(stringResource(dayLabelRes(day)))
                                }
                            }
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_start)) {
                        OutlinedButton(onClick = { startPickerOpen = true }) {
                            Text(formatMinuteOfDay(editor.startMinuteOfDay))
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_end)) {
                        OutlinedButton(onClick = { endPickerOpen = true }) {
                            Text(formatMinuteOfDay(editor.endMinuteOfDay))
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_priority)) {
                        OutlinedTextField(
                            value = editor.priorityText,
                            onValueChange = viewModel::onPriorityChange,
                            label = { Text(stringResource(R.string.schedule_priority)) },
                            supportingText = { Text(stringResource(R.string.schedule_priority_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_apps)) {
                        when {
                            !catalog.loaded -> Text(stringResource(R.string.state_loading))
                            catalog.apps.isEmpty() -> Text(stringResource(R.string.schedule_no_apps))
                            else -> catalog.apps.forEach { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.onTogglePackage(app.packageName) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = editor.isSelected(app.packageName),
                                        onCheckedChange = { viewModel.onTogglePackage(app.packageName) },
                                    )
                                    Text(app.appDisplayName)
                                }
                            }
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.schedule_enabled)) {
                        Switch(
                            checked = editor.enabled,
                            onCheckedChange = viewModel::onEnabledChange,
                            enabled = !editor.busy,
                        )
                    }
                }
                item {
                    AppLoadingButton(
                        labelRes = R.string.btn_save,
                        loading = editor.saving,
                        onClick = viewModel::save,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = onBack,
                        enabled = !editor.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.btn_cancel)) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (startPickerOpen) {
        TimePickerDialog(
            titleRes = R.string.schedule_start,
            initialMinuteOfDay = editor.startMinuteOfDay,
            onConfirm = { viewModel.onStartMinuteChange(it); startPickerOpen = false },
            onDismiss = { startPickerOpen = false },
        )
    }
    if (endPickerOpen) {
        TimePickerDialog(
            titleRes = R.string.schedule_end,
            initialMinuteOfDay = editor.endMinuteOfDay,
            onConfirm = { viewModel.onEndMinuteChange(it); endPickerOpen = false },
            onDismiss = { endPickerOpen = false },
        )
    }
}

/** A row of single-select chips over [values], labelled from resources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(
    values: List<T>,
    selected: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(stringResource(labelRes(value))) },
            )
        }
    }
}
