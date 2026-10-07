package uz.faceguard.app.core.billing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.domain.billing.EntitlementSource
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.PremiumEntitlement

/**
 * Persists the *last known* entitlement per account, so premium survives a process
 * restart and an offline launch. It is deliberately a **cache of a verified state**,
 * never the source of truth: Google Play remains authoritative, and the offline policy
 * bounds how long a cached value is honored.
 *
 * Scoped by account id, so one account's entitlement can never be read as another's.
 */
interface EntitlementStore {
    fun observe(accountId: Long): Flow<PremiumEntitlement?>

    suspend fun save(accountId: Long, entitlement: PremiumEntitlement)

    suspend fun clear(accountId: Long)
}

/**
 * DataStore-backed cache. Reuses the shared app preferences store (like the language
 * store) with account-scoped keys, so no second DataStore file is created.
 *
 * Only non-secret fields are persisted. The purchase token is never stored here.
 */
@Singleton
class DataStoreEntitlementStore @Inject constructor(
    private val store: DataStore<Preferences>,
) : EntitlementStore {

    override fun observe(accountId: Long): Flow<PremiumEntitlement?> =
        store.data.map { prefs ->
            val stateName = prefs[stateKey(accountId)] ?: return@map null
            val state = runCatching { EntitlementState.valueOf(stateName) }
                .getOrDefault(EntitlementState.UNKNOWN)
            PremiumEntitlement(
                state = state,
                productId = prefs[productKey(accountId)],
                basePlanId = prefs[basePlanKey(accountId)],
                offerId = prefs[offerKey(accountId)],
                isTrial = prefs[trialKey(accountId)] ?: false,
                autoRenewing = prefs[autoRenewKey(accountId)] ?: false,
                acknowledged = prefs[ackKey(accountId)] ?: false,
                expiryTimeMillis = prefs[expiryKey(accountId)],
                lastVerifiedAtMillis = prefs[verifiedKey(accountId)] ?: 0L,
                source = EntitlementSource.CACHE,
            )
        }

    override suspend fun save(accountId: Long, entitlement: PremiumEntitlement) {
        store.edit { prefs ->
            prefs[stateKey(accountId)] = entitlement.state.name
            entitlement.productId?.let { prefs[productKey(accountId)] = it } ?: prefs.remove(productKey(accountId))
            entitlement.basePlanId?.let { prefs[basePlanKey(accountId)] = it } ?: prefs.remove(basePlanKey(accountId))
            entitlement.offerId?.let { prefs[offerKey(accountId)] = it } ?: prefs.remove(offerKey(accountId))
            prefs[trialKey(accountId)] = entitlement.isTrial
            prefs[autoRenewKey(accountId)] = entitlement.autoRenewing
            prefs[ackKey(accountId)] = entitlement.acknowledged
            entitlement.expiryTimeMillis?.let { prefs[expiryKey(accountId)] = it }
                ?: prefs.remove(expiryKey(accountId))
            prefs[verifiedKey(accountId)] = entitlement.lastVerifiedAtMillis
        }
    }

    override suspend fun clear(accountId: Long) {
        store.edit { prefs ->
            listOf<Preferences.Key<*>>(
                stateKey(accountId), productKey(accountId), basePlanKey(accountId), offerKey(accountId),
                trialKey(accountId), autoRenewKey(accountId), ackKey(accountId), expiryKey(accountId),
                verifiedKey(accountId),
            ).forEach { prefs.remove(it) }
        }
    }

    private fun stateKey(accountId: Long) = stringPreferencesKey("entitlement_${accountId}_state")
    private fun productKey(accountId: Long) = stringPreferencesKey("entitlement_${accountId}_product")
    private fun basePlanKey(accountId: Long) = stringPreferencesKey("entitlement_${accountId}_base_plan")
    private fun offerKey(accountId: Long) = stringPreferencesKey("entitlement_${accountId}_offer")
    private fun trialKey(accountId: Long) = booleanPreferencesKey("entitlement_${accountId}_trial")
    private fun autoRenewKey(accountId: Long) = booleanPreferencesKey("entitlement_${accountId}_auto_renew")
    private fun ackKey(accountId: Long) = booleanPreferencesKey("entitlement_${accountId}_ack")
    private fun expiryKey(accountId: Long) = longPreferencesKey("entitlement_${accountId}_expiry")
    private fun verifiedKey(accountId: Long) = longPreferencesKey("entitlement_${accountId}_verified")
}
