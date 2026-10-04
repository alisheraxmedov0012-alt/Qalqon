package uz.faceguard.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import uz.faceguard.app.core.billing.BillingGateway
import uz.faceguard.app.core.billing.DataStoreEntitlementStore
import uz.faceguard.app.core.billing.EntitlementStore
import uz.faceguard.app.core.billing.GooglePlayBillingGateway
import uz.faceguard.app.core.billing.SubscriptionManager
import uz.faceguard.app.domain.billing.EntitlementRepository

/**
 * Billing bindings. The entitlement/purchase logic depends only on the
 * [BillingGateway] and [EntitlementStore] seams, so the Google Play implementation can
 * be swapped for a fake in tests without touching the domain or UI.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {

    @Binds
    @Singleton
    abstract fun bindEntitlementStore(impl: DataStoreEntitlementStore): EntitlementStore

    @Binds
    @Singleton
    abstract fun bindBillingGateway(impl: GooglePlayBillingGateway): BillingGateway

    @Binds
    @Singleton
    abstract fun bindEntitlementRepository(impl: SubscriptionManager): EntitlementRepository
}
