package uz.faceguard.app.feature.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import uz.faceguard.app.core.ui.UiState
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository

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

    fun confirmDelete() {
        val id = accountId ?: return
        val childId = _ui.value.deleteConfirm.childId
        _ui.update { it.copy(deleteConfirm = DeleteConfirm()) }
        viewModelScope.launch { childRepository.deleteChild(id, childId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfilesScreen(
    onBack: () -> Unit,
    onEnrollChild: (Long) -> Unit,
    viewModel: ChildProfilesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.children_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.children_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::openAddDialog) {
                    Text(stringResource(R.string.children_add))
                }
            }
            when (ui.state) {
                is UiState.Loading ->
                    Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyLarge)
                is UiState.Error ->
                    Text(
                        stringResource((ui.state as UiState.Error).messageRes),
                        color = MaterialTheme.colorScheme.error,
                    )
                is UiState.Success, is UiState.Idle -> {
                    if (ui.children.isEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.children_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ui.children, key = { it.id }) { child ->
                                ChildCard(
                                    child = child,
                                    onEdit = { viewModel.openEditDialog(child) },
                                    onDelete = { viewModel.requestDelete(child) },
                                )
                            }
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.children_level_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LevelRow(
                        RestrictionLevel.LOW,
                        ui.dialog.level,
                        viewModel::onLevelChange,
                        R.string.level_low,
                    )
                    LevelRow(
                        RestrictionLevel.MEDIUM,
                        ui.dialog.level,
                        viewModel::onLevelChange,
                        R.string.level_medium,
                    )
                    LevelRow(
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

@Composable
private fun LevelRow(
    level: RestrictionLevel,
    selected: RestrictionLevel,
    onSelect: (RestrictionLevel) -> Unit,
    labelRes: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected == level, onCheckedChange = { onSelect(level) })
        Text(stringResource(labelRes))
    }
}

@Composable
private fun ChildCard(
    child: ChildProfile,
    onEnrollFace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(child.childName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (child.restrictionLevel) {
                        RestrictionLevel.LOW -> stringResource(R.string.level_low)
                        RestrictionLevel.MEDIUM -> stringResource(R.string.level_medium)
                        RestrictionLevel.HIGH -> stringResource(R.string.level_high)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(
                        if (child.isFaceEnrolled) R.string.children_face_on
                        else R.string.children_face_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (child.isFaceEnrolled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onEnrollFace) {
                Icon(Icons.Filled.Face, contentDescription = stringResource(R.string.btn_enroll_face))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.btn_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.btn_delete))
            }
        }
    }
}
