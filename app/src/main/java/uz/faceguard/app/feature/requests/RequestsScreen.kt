package uz.faceguard.app.feature.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.notification.AppLabelResolver
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.notification.DefaultNotificationPolicy
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestResolutionResult
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository

/** Phase 11: parent-facing request screen. */

enum class RequestsStatus { LOADING, READY, NO_ACCOUNT, ERROR }

/** One row: the durable request plus the parent-facing labels for it. */
data class RequestRow(
    val request: ParentRequest,
    val childName: String?,
    val appLabel: String,
)

data class RequestsUiState(
    val status: RequestsStatus = RequestsStatus.LOADING,
    val errorMessageRes: Int? = null,
    val requests: List<RequestRow> = emptyList(),
    val pendingCount: Int = 0,
    val busyRequestIds: Set<Long> = emptySet(),
    val notificationsEnabled: Boolean = true,
) {
    val hasRequests: Boolean get() = requests.isNotEmpty()
    val hasPending: Boolean get() = pendingCount > 0
}

/** Pure: domain status -> localized label. */
fun requestStatusLabelRes(status: RequestStatus): Int = when (status) {
    RequestStatus.PENDING -> R.string.request_status_pending
    RequestStatus.APPROVED -> R.string.request_status_approved
    RequestStatus.REJECTED -> R.string.request_status_rejected
    RequestStatus.EXPIRED -> R.string.request_status_expired
    RequestStatus.CANCELLED -> R.string.request_status_cancelled
}

/** Pure: build the rows (child name by stable id, app label already resolved). */
fun requestRows(
    requests: List<ParentRequest>,
    childrenById: Map<Long, String>,
    appLabel: (String) -> String,
): List<RequestRow> = requests.map { request ->
    RequestRow(
        request = request,
        childName = childrenById[request.childId],
        appLabel = appLabel(request.targetPackageName),
    )
}

@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val childProfileRepository: ChildProfileRepository,
    private val requestRepository: ParentRequestRepository,
    private val appLabelResolver: AppLabelResolver,
    private val notificationDispatcher: AppNotificationDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(RequestsUiState())
    val state: StateFlow<RequestsUiState> = _state

    /** Set when the screen was opened from a request notification (informational). */
    val highlightedRequestId: Long =
        savedStateHandle.get<Long>(RequestsArgs.REQUEST_ID) ?: NO_REQUEST

    private var accountId: Long? = null
    private var requests: List<ParentRequest> = emptyList()
    private var childrenById: Map<Long, String> = emptyMap()

    init {
        viewModelScope.launch {
            // Account-scoped: collectLatest cancels the previous account's
            // collectors, so a stale account can never push data into this state.
            accountRepository.currentAccountId
                .distinctUntilChanged()
                .collectLatest { id ->
                    accountId = id
                    requests = emptyList()
                    childrenById = emptyMap()
                    _state.value = RequestsUiState(
                        status = if (id == null) RequestsStatus.NO_ACCOUNT else RequestsStatus.LOADING,
                        notificationsEnabled = notificationsEnabled(),
                    )
                    if (id != null) observeAccount(id)
                }
        }
    }

    private suspend fun observeAccount(owner: Long) = coroutineScope {
        // Expiration is evaluated on access (idempotent) — no permanent poller.
        runCatching { requestRepository.expireStale(owner, System.currentTimeMillis()) }
        launch {
            runCatching {
                childProfileRepository.observeChildren(owner).collect { children ->
                    childrenById = children.associate { it.id to it.childName }
                    rebuild()
                }
            }.onFailure { fail() }
        }
        launch {
            runCatching {
                requestRepository.observeForAccount(owner).collect { loaded ->
                    requests = loaded
                    rebuild()
                }
            }.onFailure { fail() }
        }
    }

    private fun rebuild() {
        _state.value = _state.value.copy(
            status = RequestsStatus.READY,
            errorMessageRes = null,
            requests = requestRows(requests, childrenById) { appLabelResolver.labelFor(it) },
            pendingCount = requests.count { it.isPending },
            notificationsEnabled = notificationsEnabled(),
        )
    }

    private fun fail() {
        _state.value = _state.value.copy(status = RequestsStatus.ERROR, errorMessageRes = R.string.request_error)
    }

    private fun notificationsEnabled(): Boolean =
        runCatching { notificationDispatcher.areNotificationsEnabled() }.getOrDefault(true)

    fun retry() {
        val owner = accountId ?: return
        _state.value = RequestsUiState(status = RequestsStatus.LOADING, notificationsEnabled = notificationsEnabled())
        viewModelScope.launch { observeAccount(owner) }
    }

    fun approve(requestId: Long) =
        resolve(requestId) { owner, now -> requestRepository.approve(owner, requestId, null, now) }

    fun reject(requestId: Long) =
        resolve(requestId) { owner, now -> requestRepository.reject(owner, requestId, now) }

    /**
     * The repository is the authority: it only updates a still-pending request of
     * this account, so rapid or late taps cannot produce contradictory state. The
     * busy set drives the UI only.
     */
    private fun resolve(
        requestId: Long,
        action: suspend (Long, Long) -> RequestResolutionResult,
    ) {
        val owner = accountId ?: return
        if (requestId in _state.value.busyRequestIds) return
        _state.value = _state.value.copy(busyRequestIds = _state.value.busyRequestIds + requestId)
        viewModelScope.launch {
            runCatching { action(owner, System.currentTimeMillis()) }.onFailure { fail() }
            // A resolved request must not leave a stale notification behind.
            notificationDispatcher.cancel(DefaultNotificationPolicy.requestNotificationId(requestId))
            _state.value = _state.value.copy(busyRequestIds = _state.value.busyRequestIds - requestId)
        }
    }

    companion object {
        const val NO_REQUEST = -1L
    }
}

/** Navigation contract for the requests route. */
object RequestsArgs {
    const val REQUEST_ID = "requestId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    onBack: () -> Unit,
    viewModel: RequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.requests_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.request_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.requests_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.notificationsEnabled) {
                item {
                    Text(
                        stringResource(R.string.request_notifications_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            when (state.status) {
                RequestsStatus.LOADING -> item {
                    Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyMedium)
                }

                RequestsStatus.ERROR -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(state.errorMessageRes ?: R.string.request_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.request_retry))
                            }
                        }
                    }
                }

                RequestsStatus.NO_ACCOUNT -> item {
                    Text(stringResource(R.string.dashboard_no_account), style = MaterialTheme.typography.bodyMedium)
                }

                RequestsStatus.READY -> {
                    if (!state.hasRequests) {
                        item {
                            Text(
                                stringResource(R.string.request_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(state.requests, key = { it.request.id }) { row ->
                            RequestCard(
                                row = row,
                                busy = row.request.id in state.busyRequestIds,
                                onApprove = { viewModel.approve(row.request.id) },
                                onReject = { viewModel.reject(row.request.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestCard(
    row: RequestRow,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val request = row.request
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.appLabel, style = MaterialTheme.typography.titleMedium)
            row.childName?.let { name ->
                Text(
                    stringResource(R.string.request_child, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.request_duration, request.requestedDurationMinutes),
                style = MaterialTheme.typography.bodyMedium,
            )
            request.approvedDurationMinutes?.let { approved ->
                Text(
                    stringResource(R.string.request_approved_duration, approved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.request_status,
                    stringResource(requestStatusLabelRes(request.status)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (request.isPending) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (request.isPending) {
                // Actions exist only while the request is genuinely pending; a
                // resolved request never shows decision buttons.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.request_approve))
                    }
                    OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.request_reject))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.request_resolved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
