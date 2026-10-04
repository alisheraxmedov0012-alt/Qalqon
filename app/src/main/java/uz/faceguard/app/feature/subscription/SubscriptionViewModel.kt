package uz.faceguard.app.feature.subscription

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.core.billing.SubscriptionManager
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.RefreshOutcome

/** Transient UI state for the subscription screen. No secrets, no billing types. */
data class SubscriptionUiState(
    val loading: Boolean = false,
    val error: BillingError? = null,
    /** Set briefly after a successful restore, so the screen can confirm it. */
    val restored: Boolean = false,
)

/**
 * Drives the subscription screen. It reads the single entitlement source and delegates
 * every action (load product, refresh, restore, purchase) to [SubscriptionManager]; it
 * contains no billing logic of its own.
 */
@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val manager: SubscriptionManager,
) : ViewModel() {

    val entitlement = manager.entitlement
    val product = manager.product
    val premiumActive = manager.premiumActive

    private val _ui = MutableStateFlow(SubscriptionUiState())
    val ui: StateFlow<SubscriptionUiState> = _ui.asStateFlow()

    init {
        load()
    }

    /** Loads dynamic price/trial and re-verifies the entitlement. */
    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            manager.loadProduct()
            manager.refresh()
            _ui.update { it.copy(loading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            manager.refresh()
            _ui.update { it.copy(loading = false) }
        }
    }

    /** Restore purchase: a real Play query, then entitlement refresh (PHASE Q). */
    fun restore() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, restored = false) }
            val outcome = manager.restore()
            val restored = outcome is RefreshOutcome.Verified && outcome.entitlement.grantsAccess
            _ui.update { it.copy(loading = false, restored = restored) }
        }
    }

    /** Launches the Play purchase sheet for the loaded product. */
    fun subscribe(activity: Activity) {
        viewModelScope.launch {
            val details = product.value
            if (details == null) {
                _ui.update { it.copy(error = BillingError.ITEM_UNAVAILABLE) }
                return@launch
            }
            _ui.update { it.copy(error = null) }
            val launchError = manager.launchPurchase(activity, details)
            if (launchError != null) _ui.update { it.copy(error = launchError) }
        }
    }

    fun consumeError() = _ui.update { it.copy(error = null) }

    fun consumeRestored() = _ui.update { it.copy(restored = false) }
}
