package uz.faceguard.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository

/**
 * UI/UX redesign, Phase 6: the Settings hub's small read-only status source.
 *
 * Supplies the two live facts the hub shows — the parent's display name for the
 * compact identity block, and whether the OS would actually show QALQON's
 * notifications. Both come from the existing sources of truth (the profile store and
 * the notification dispatcher); nothing here is a second state or a fabricated value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsStatusViewModel @Inject constructor(
    accountRepository: AccountRepository,
    parentProfileRepository: ParentProfileRepository,
    private val notificationDispatcher: AppNotificationDispatcher,
) : ViewModel() {

    /** The signed-in parent's display name, or `null` before it is known. */
    val parentName: StateFlow<String?> = accountRepository.currentAccountId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else parentProfileRepository.observe(id)
        }
        .map { it?.displayName?.takeIf { name -> name.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _notificationsEnabled = MutableStateFlow(readNotificationsEnabled())
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled

    /**
     * Re-reads the notification capability. Called when the hub/notifications screen
     * appears, so a change made in system settings is reflected on return; a failing
     * probe degrades to "enabled" (the honest default, matching the runtime's own).
     */
    fun refreshNotifications() {
        _notificationsEnabled.value = readNotificationsEnabled()
    }

    private fun readNotificationsEnabled(): Boolean =
        runCatching { notificationDispatcher.areNotificationsEnabled() }.getOrDefault(true)
}
