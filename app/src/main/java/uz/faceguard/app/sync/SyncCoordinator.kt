package uz.faceguard.app.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fire-and-forget sync fan-out. Callers may invoke this after local writes;
 * with the default gateways it is a no-op and never touches the network.
 * A future backend can swap the gateways in DI without changing call sites.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val accountGateway: AccountSyncGateway,
    private val childGateway: ChildProfileSyncGateway,
    private val settingsGateway: SettingsSyncGateway,
) {
    /** Called after the account is created; safe to no-op offline. */
    fun onAccountChanged(scope: CoroutineScope, account: uz.faceguard.app.domain.model.UserAccount) =
        scope.launch { accountGateway.push(account) }

    /** Called after child profiles change; safe to no-op offline. */
    fun onChildrenChanged(scope: CoroutineScope, children: List<uz.faceguard.app.domain.model.ChildProfile>) =
        scope.launch { childGateway.pushAll(children) }

    /** Called after settings change; safe to no-op offline. */
    fun onSettingsChanged(scope: CoroutineScope, settings: uz.faceguard.app.domain.model.AppSettings) =
        scope.launch { settingsGateway.push(settings) }
}
