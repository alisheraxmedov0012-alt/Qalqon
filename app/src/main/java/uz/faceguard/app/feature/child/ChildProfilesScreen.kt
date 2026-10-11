package uz.faceguard.app.feature.child

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonShapes
import uz.faceguard.app.core.theme.QalqonTheme
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonEmptyState
import uz.faceguard.app.core.ui.qalqon.QalqonErrorState
import uz.faceguard.app.core.ui.qalqon.QalqonLoadingState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.core.ui.qalqon.QalqonStatusBadge
import uz.faceguard.app.core.ui.qalqon.toneColor
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.screentime.ScreenTimeActiveChildRepository
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluation
import uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluator
import uz.faceguard.app.domain.screentime.ScreenTimeLimitRepository
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageDateKey
import uz.faceguard.app.feature.home.durationLabel

data class ChildDialogState(
    val visible: Boolean = false,
    val editingId: Long? = null,
    val name: String = "",
    val level: RestrictionLevel = RestrictionLevel.MEDIUM,
) {
    val isEditing: Boolean get() = editingId != null
}

data class DeleteConfirm(
    val visible: Boolean = false,
    val childId: Long = 0,
    val childName: String = "",
)

data class ChildUiState(
    val state: UiState = UiState.Idle,
    val children: List<ChildProfile> = emptyList(),
    /**
     * Today's screen-time facts per child id. A child absent here has no readable facts —
     * an unknown value is never replaced with a fabricated `0 min`.
     */
    val screenTime: Map<Long, ChildScreenTime> = emptyMap(),
    val dialog: ChildDialogState = ChildDialogState(),
    val deleteConfirm: DeleteConfirm = DeleteConfirm(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChildProfilesViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val childRepository: ChildProfileRepository,
    /** Phase 4 Step 1B-8: the screen-time target is cleared with the child it points at. */
    private val screenTimeActiveChildRepository: ScreenTimeActiveChildRepository,
    /**
     * The Children list's per-child screen-time facts. The list reuses the same domain
     * evaluator Home uses, so a limit is compared in exactly one place and the card never
     * recomputes `used >= limit` itself.
     */
    private val screenTimeUsageRepository: ScreenTimeUsageRepository,
    private val screenTimeLimitRepository: ScreenTimeLimitRepository,
    private val screenTimeEvaluator: ScreenTimeLimitEvaluator,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChildUiState(state = UiState.Loading))
    val ui: StateFlow<ChildUiState> = _ui

    private var accountId: Long? = null

    init {
        viewModelScope.launch {
            val account = accountRepository.getCurrentAccount()
            if (account == null) {
                _ui.update { it.copy(state = UiState.Error(R.string.error_invalid_credentials)) }
            } else {
                accountId = account.id
                childRepository.observeChildren(account.id)
                    .flatMapLatest { children ->
                        val dateKey = UsageDateKey.of(System.currentTimeMillis())
                        screenTimeFacts(account.id, children, dateKey)
                            .map { facts -> children to facts }
                    }
                    .collect { (children, facts) ->
                        _ui.update {
                            it.copy(state = UiState.Success, children = children, screenTime = facts)
                        }
                    }
            }
        }
    }

    /**
     * Observes each child's real usage and configured limits and re-evaluates through
     * [ScreenTimeLimitEvaluator]. The inner flows are cancelled and rebuilt whenever the
     * child set changes, so an added or removed child is reflected without polling.
     */
    private fun screenTimeFacts(
        accountId: Long,
        children: List<ChildProfile>,
        dateKey: String,
    ): Flow<Map<Long, ChildScreenTime>> {
        if (children.isEmpty()) return flowOf(emptyMap())

        val perChild: List<Flow<Pair<Long, ChildScreenTime>?>> = children.map { child ->
            combine(
                screenTimeUsageRepository.observeDayUsage(accountId, child.id, dateKey),
                screenTimeLimitRepository.observeLimits(accountId, child.id),
            ) { _, _ -> Unit }
                .map { screenTimeEvaluator.evaluateTotal(accountId, child.id, dateKey).toChildScreenTime() }
                .map<ChildScreenTime, Pair<Long, ChildScreenTime>?> { facts -> child.id to facts }
                // A read failure leaves the child without facts rather than claiming zero usage.
                .catch { emit(null) }
        }

        return combine(perChild) { results -> results.filterNotNull().toMap() }
    }

    fun openAddDialog() = _ui.update { it.copy(dialog = ChildDialogState(visible = true)) }

    fun openEditDialog(child: ChildProfile) = _ui.update {
        it.copy(
            dialog = ChildDialogState(
                visible = true,
                editingId = child.id,
                name = child.childName,
                level = child.restrictionLevel,
            ),
        )
    }

    fun dismissDialog() = _ui.update { it.copy(dialog = ChildDialogState()) }
    fun onNameChange(value: String) = _ui.update { it.copy(dialog = it.dialog.copy(name = value)) }
    fun onLevelChange(level: RestrictionLevel) = _ui.update { it.copy(dialog = it.dialog.copy(level = level)) }

    fun saveDialog() {
        val id = accountId ?: return
        val dialog = _ui.value.dialog
        val name = dialog.name.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            if (dialog.isEditing) {
                childRepository.updateChild(id, dialog.editingId!!, name, dialog.level)
            } else {
                childRepository.addChild(id, name, dialog.level)
            }
            _ui.update { it.copy(dialog = ChildDialogState()) }
        }
    }

    fun requestDelete(child: ChildProfile) = _ui.update {
        it.copy(deleteConfirm = DeleteConfirm(visible = true, childId = child.id, childName = child.childName))
    }

    fun cancelDelete() = _ui.update { it.copy(deleteConfirm = DeleteConfirm()) }

    /**
     * Phase 4 Step 1B-8: deleting a child also clears the device's screen-time target when
     * it pointed at that child, so the stored configuration cannot dangle. No other child
     * is substituted — the collector already refuses an unowned id, and the parent chooses
     * the next target themselves.
     */
    fun confirmDelete() {
        val id = accountId ?: return
        val childId = _ui.value.deleteConfirm.childId
        _ui.update { it.copy(deleteConfirm = DeleteConfirm()) }
        viewModelScope.launch {
            childRepository.deleteChild(id, childId)
            if (screenTimeActiveChildRepository.activeChildId(id) == childId) {
                screenTimeActiveChildRepository.clearActiveChildId(id)
            }
        }
    }
}

/** Copies the evaluator's facts verbatim; the arithmetic stays in the domain. */
private fun ScreenTimeLimitEvaluation.toChildScreenTime(): ChildScreenTime = ChildScreenTime(
    usedMs = usedMs,
    limitMinutes = limitMinutes,
    exceeded = exceeded,
    invalidLimit = invalidLimitMinutes != null,
)

/**
 * UI/UX redesign, Phase 4: the Children top-level destination.
 *
 * A scannable list of the parent's children rather than a per-child settings dump.
 * Each card carries the child's real state — avatar, status badge, today's screen time
 * and the child-scoped destinations as action chips — while the card's primary action
 * opens the Child Detail hub. Edit, delete and face enrollment stay available as
 * secondary actions, and add / edit / delete reuse the existing
 * [ChildProfilesViewModel] unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfilesScreen(
    onBack: () -> Unit,
    onOpenChild: (Long) -> Unit,
    onEnrollChild: (Long) -> Unit,
    onOpenSchedule: (Long) -> Unit = {},
    onOpenEyeSafety: (Long) -> Unit = {},
    viewModel: ChildProfilesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var menuChildId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.children_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.child_detail_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openAddDialog) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.children_add),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (val state = ui.state) {
            is UiState.Loading, is UiState.Idle -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonLoadingState()
            }

            is UiState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                QalqonErrorState(title = stringResource(state.messageRes))
            }

            is UiState.Success -> {
                val overviews = childOverviews(ui.children, ui.screenTime)
                if (overviews.isEmpty()) {
                    EmptyChildren(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onAdd = viewModel::openAddDialog,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(
                            start = QalqonDimens.screenPadding,
                            end = QalqonDimens.screenPadding,
                            top = QalqonDimens.spacing.lg,
                            bottom = QalqonDimens.spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
                    ) {
                        item { QalqonSectionHeader(title = stringResource(R.string.children_overview)) }
                        items(overviews, key = { it.childId }) { overview ->
                            val child = ui.children.first { it.id == overview.childId }
                            ChildProfileCard(
                                overview = overview,
                                menuExpanded = menuChildId == overview.childId,
                                onOpenMenu = { menuChildId = overview.childId },
                                onDismissMenu = { menuChildId = null },
                                onOpenDetail = { onOpenChild(overview.childId) },
                                onOpenSchedule = { onOpenSchedule(overview.childId) },
                                onOpenEyeSafety = { onOpenEyeSafety(overview.childId) },
                                onEnrollFace = {
                                    menuChildId = null
                                    onEnrollChild(overview.childId)
                                },
                                onEdit = {
                                    menuChildId = null
                                    viewModel.openEditDialog(child)
                                },
                                onDelete = {
                                    menuChildId = null
                                    viewModel.requestDelete(child)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (ui.dialog.visible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = {
                Text(
                    stringResource(
                        if (ui.dialog.isEditing) R.string.children_edit else R.string.children_add,
                    ),
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = ui.dialog.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.children_name_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(QalqonDimens.spacing.md))
                    Text(
                        stringResource(R.string.children_level_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LevelOption(
                        RestrictionLevel.LOW,
                        ui.dialog.level,
                        viewModel::onLevelChange,
                        R.string.level_low,
                    )
                    LevelOption(
                        RestrictionLevel.MEDIUM,
                        ui.dialog.level,
                        viewModel::onLevelChange,
                        R.string.level_medium,
                    )
                    LevelOption(
                        RestrictionLevel.HIGH,
                        ui.dialog.level,
                        viewModel::onLevelChange,
                        R.string.level_high,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveDialog) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    if (ui.deleteConfirm.visible) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.children_delete_title)) },
            text = { Text(stringResource(R.string.children_delete_message, ui.deleteConfirm.childName)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** The empty list: nothing to scan yet, with a single pill CTA to add the first child. */
@Composable
private fun EmptyChildren(modifier: Modifier, onAdd: () -> Unit) {
    Box(modifier = modifier) {
        QalqonEmptyState(
            title = stringResource(R.string.children_empty),
            description = stringResource(R.string.children_empty_hint),
            icon = {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(QalqonDimens.icon.lg),
                )
            },
            actionLabel = stringResource(R.string.children_add),
            onAction = onAdd,
            actionShape = QalqonShapes.pillShape,
        )
    }
}

/**
 * One child's card: avatar, name, real status badge, today's screen-time progress and the
 * child-scoped action chips, plus the existing overflow holding edit/delete (and face
 * enrollment when the child still needs it).
 */
@Composable
private fun ChildProfileCard(
    overview: ChildOverview,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenEyeSafety: () -> Unit,
    onEnrollFace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = overview.status
    val statusLabel = stringResource(childCardStatusLabelRes(status))
    val cardDescription = stringResource(
        R.string.home_child_content_description,
        overview.name,
        statusLabel,
    )
    val openDetailsLabel = stringResource(R.string.children_open_details, overview.name)

    Box {
        QalqonCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = cardDescription },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = QalqonShapes.xLargeShape,
            onClick = onOpenDetail,
            onClickLabel = openDetailsLabel,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChildAvatarContainer(initial = overview.initial, status = status)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = QalqonDimens.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
                ) {
                    Text(
                        text = overview.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    QalqonStatusBadge(
                        label = statusLabel,
                        tone = childCardStatusTone(status),
                    )
                    ScreenTimeProgress(overview.screenTime, toneColor(childCardStatusTone(status)))
                }
                IconButton(onClick = onOpenMenu) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.home_more_options),
                    )
                }
            }
            ChildActionChips(
                onOpenSettings = onOpenDetail,
                onOpenSchedule = onOpenSchedule,
                onOpenEyeSafety = onOpenEyeSafety,
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            if (!overview.faceEnrolled) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.btn_enroll_face)) },
                    onClick = onEnrollFace,
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_edit)) },
                onClick = onEdit,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_delete)) },
                onClick = onDelete,
            )
        }
    }
}

/** Circular avatar disc, tinted from the semantic palette by the child's status. */
@Composable
private fun ChildAvatarContainer(initial: String, status: ChildCardStatus) {
    val container = when (status) {
        ChildCardStatus.ACTIVE -> QalqonTheme.colors.successContainer
        ChildCardStatus.TIME_UP -> QalqonTheme.colors.warningContainer
        ChildCardStatus.OFFLINE -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(QalqonDimens.sizes.avatar)
            .background(color = container, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Today's screen time: the used amount from real usage data, and a progress bar only when a
 * valid daily limit exists (an unlimited child has no bar rather than a fabricated one).
 */
@Composable
private fun ScreenTimeProgress(screenTime: ChildScreenTime?, accent: Color) {
    if (screenTime == null) return
    Text(
        text = stringResource(R.string.screentime_app_used, durationLabel(screenTime.usedMs)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val progress = screenTime.progress
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** The child-scoped destinations as action chips; each opens an existing route. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChildActionChips(
    onOpenSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenEyeSafety: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
    ) {
        AssistChip(
            onClick = onOpenSettings,
            label = { Text(stringResource(R.string.children_action_settings)) },
        )
        AssistChip(
            onClick = onOpenSchedule,
            label = { Text(stringResource(R.string.children_action_schedule)) },
        )
        AssistChip(
            onClick = onOpenEyeSafety,
            label = { Text(stringResource(R.string.children_action_eye_safety)) },
        )
    }
}

@Composable
private fun LevelOption(
    level: RestrictionLevel,
    selected: RestrictionLevel,
    onSelect: (RestrictionLevel) -> Unit,
    labelRes: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == level, onClick = { onSelect(level) })
        Text(stringResource(labelRes))
    }
}
