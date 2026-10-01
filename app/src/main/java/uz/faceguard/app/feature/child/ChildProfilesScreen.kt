package uz.faceguard.app.feature.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.core.ui.qalqon.QalqonChildCard
import uz.faceguard.app.core.ui.qalqon.QalqonEmptyState
import uz.faceguard.app.core.ui.qalqon.QalqonErrorState
import uz.faceguard.app.core.ui.qalqon.QalqonLoadingState
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.screentime.ScreenTimeActiveChildRepository

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
    val dialog: ChildDialogState = ChildDialogState(),
    val deleteConfirm: DeleteConfirm = DeleteConfirm(),
)

@HiltViewModel
class ChildProfilesViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val childRepository: ChildProfileRepository,
    /** Phase 4 Step 1B-8: the screen-time target is cleared with the child it points at. */
    private val screenTimeActiveChildRepository: ScreenTimeActiveChildRepository,
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
                childRepository.observeChildren(account.id).collect { children ->
                    _ui.update { it.copy(state = UiState.Success, children = children) }
                }
            }
        }
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

/**
 * UI/UX redesign, Phase 4: the Children top-level destination.
 *
 * A scannable list of the parent's children rather than a per-child settings dump.
 * The card's primary action opens the Child Detail hub, where every child-scoped
 * control lives; edit, delete and face enrollment stay available as secondary actions
 * so no existing capability is lost. Add / edit / delete reuse the existing
 * [ChildProfilesViewModel] unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfilesScreen(
    onBack: () -> Unit,
    onOpenChild: (Long) -> Unit,
    onEnrollChild: (Long) -> Unit,
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
                val overviews = childOverviews(ui.children)
                if (overviews.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        QalqonEmptyState(
                            title = stringResource(R.string.children_empty),
                            description = stringResource(R.string.children_empty_hint),
                            actionLabel = stringResource(R.string.children_add),
                            onAction = viewModel::openAddDialog,
                        )
                    }
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
                            ChildListRow(
                                overview = overview,
                                menuExpanded = menuChildId == overview.childId,
                                onOpenMenu = { menuChildId = overview.childId },
                                onDismissMenu = { menuChildId = null },
                                onOpenDetail = { onOpenChild(overview.childId) },
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

/**
 * One child in the list: a tappable [QalqonChildCard] whose primary action opens the
 * Child Detail hub, plus a secondary overflow holding the existing edit/delete (and
 * face enrollment when the child still needs it).
 */
@Composable
private fun ChildListRow(
    overview: ChildOverview,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenDetail: () -> Unit,
    onEnrollFace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val faceStatus = stringResource(
        if (overview.faceEnrolled) R.string.children_face_on else R.string.children_face_off,
    )
    Box {
        QalqonChildCard(
            name = overview.name,
            initial = overview.initial,
            protectionConfigured = overview.faceEnrolled,
            faceEnrolled = overview.faceEnrolled,
            faceStatus = faceStatus,
            onClick = onOpenDetail,
            onClickLabel = stringResource(R.string.children_open_details, overview.name),
            contentDescription = stringResource(
                R.string.home_child_content_description,
                overview.name,
                faceStatus,
            ),
            trailing = {
                IconButton(onClick = onOpenMenu) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.home_more_options),
                    )
                }
            },
        )
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
