package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.domain.screentime.ScreenTimeActiveChildRepository

/**
 * Phase 4 Step 1B-7: the screen-time target, stored in the existing settings DataStore.
 *
 * A thin adapter over [SettingsStore] on purpose: the account-scoped key machinery,
 * scoping prefix and wipe behaviour already exist there, so there is no second storage,
 * no second key format and nothing to keep in sync. The domain sees only
 * [ScreenTimeActiveChildRepository], never DataStore.
 */
@Singleton
class ScreenTimeActiveChildRepositoryImpl @Inject constructor(
    private val settingsStore: SettingsStore,
) : ScreenTimeActiveChildRepository {

    override suspend fun activeChildId(accountId: Long): Long? = settingsStore.activeChildId(accountId)

    override fun observeActiveChildId(accountId: Long): Flow<Long?> =
        settingsStore.observeActiveChildId(accountId)

    override suspend fun setActiveChildId(accountId: Long, childId: Long) =
        settingsStore.setActiveChildId(accountId, childId)

    override suspend fun clearActiveChildId(accountId: Long) =
        settingsStore.clearActiveChildId(accountId)
}
