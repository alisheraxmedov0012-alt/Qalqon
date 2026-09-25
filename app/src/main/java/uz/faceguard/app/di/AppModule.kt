package uz.faceguard.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton
import uz.faceguard.app.data.db.ActivityEventDao
import uz.faceguard.app.data.db.ChildAppPolicyDao
import uz.faceguard.app.data.db.ChildProfileDao
import uz.faceguard.app.data.db.DailyAppUsageDao
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.db.MIGRATION_3_4
import uz.faceguard.app.data.db.MIGRATION_4_5
import uz.faceguard.app.data.db.MIGRATION_5_6
import uz.faceguard.app.data.db.MIGRATION_6_7
import uz.faceguard.app.data.db.ParentProfileDao
import uz.faceguard.app.data.db.NotificationRecordDao
import uz.faceguard.app.data.db.ParentRequestDao
import uz.faceguard.app.data.db.ProtectedAppDao
import uz.faceguard.app.data.db.UserAccountDao
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.data.repository.ActivityLogRepositoryImpl
import uz.faceguard.app.data.repository.ChildAppPolicyRepositoryImpl
import uz.faceguard.app.data.repository.NotificationRepositoryImpl
import uz.faceguard.app.data.repository.ParentRequestRepositoryImpl
import uz.faceguard.app.data.repository.PolicySettingsRepositoryImpl
import uz.faceguard.app.data.repository.ResetRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentProfileRepositoryImpl
import uz.faceguard.app.data.repository.ProtectedAppsRepositoryImpl
import uz.faceguard.app.data.repository.ScreenTimeUsageRepositoryImpl
import uz.faceguard.app.core.embed.FaceEmbeddingModel
import uz.faceguard.app.core.embed.TfLiteMobileFaceNet
import uz.faceguard.app.core.time.SystemElapsedTimeSource
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.core.protection.AndroidProtectionServiceLauncher
import uz.faceguard.app.core.protection.ProtectionServiceLauncher
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.data.repository.SettingsRepositoryImpl
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ResetRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.policy.PolicyEvaluator
import uz.faceguard.app.domain.policy.PolicySettingsRepository
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.notification.DefaultNotificationPolicy
import uz.faceguard.app.domain.notification.NotificationContentFactory
import uz.faceguard.app.domain.notification.NotificationCoordinator
import uz.faceguard.app.domain.notification.NotificationPolicy
import uz.faceguard.app.domain.notification.NotificationRepository
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.screentime.ElapsedTimeSource
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.core.notification.AndroidNotificationContentFactory
import uz.faceguard.app.core.notification.AppLabelResolver
import uz.faceguard.app.core.security.AesGcmSecureCrypto
import uz.faceguard.app.core.security.AndroidKeystoreKeyProvider
import uz.faceguard.app.core.security.KeystoreBiometricTemplateCipher
import uz.faceguard.app.core.security.SecureCrypto
import uz.faceguard.app.core.security.SecureKeyProvider
import uz.faceguard.app.core.security.SecurityStateHolder
import uz.faceguard.app.domain.security.BiometricTemplateCipher
import uz.faceguard.app.core.notification.AndroidNotificationDispatcher
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository
import uz.faceguard.app.sync.AccountSyncGateway
import uz.faceguard.app.sync.ChildProfileSyncGateway
import uz.faceguard.app.sync.NoOpAccountSyncGateway
import uz.faceguard.app.sync.NoOpChildProfileSyncGateway
import uz.faceguard.app.sync.NoOpSettingsSyncGateway
import uz.faceguard.app.sync.SettingsSyncGateway
import uz.faceguard.app.sync.SyncCoordinator

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, "faceguard.db")
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7) // additive v3 -> v7; keeps existing user data
            .build()

    @Provides fun provideUserAccountDao(db: FaceGuardDatabase): UserAccountDao = db.userAccountDao()
    @Provides fun provideParentProfileDao(db: FaceGuardDatabase): ParentProfileDao = db.parentProfileDao()
    @Provides fun provideChildProfileDao(db: FaceGuardDatabase): ChildProfileDao = db.childProfileDao()
    @Provides fun provideProtectedAppDao(db: FaceGuardDatabase): ProtectedAppDao = db.protectedAppDao()
    @Provides fun provideActivityEventDao(db: FaceGuardDatabase): ActivityEventDao = db.activityEventDao()
    @Provides fun provideChildAppPolicyDao(db: FaceGuardDatabase): ChildAppPolicyDao = db.childAppPolicyDao()
    @Provides fun provideParentRequestDao(db: FaceGuardDatabase): ParentRequestDao = db.parentRequestDao()
    @Provides fun provideNotificationRecordDao(db: FaceGuardDatabase): NotificationRecordDao = db.notificationRecordDao()
    @Provides fun provideDailyAppUsageDao(db: FaceGuardDatabase): DailyAppUsageDao = db.dailyAppUsageDao()

    @Provides
    @Singleton
    fun provideAccountRepository(impl: AccountRepositoryImpl): AccountRepository = impl

    @Provides
    @Singleton
    fun provideParentProfileRepository(impl: ParentProfileRepositoryImpl): ParentProfileRepository = impl

    @Provides
    @Singleton
    fun provideChildProfileRepository(impl: ChildProfileRepositoryImpl): ChildProfileRepository = impl

    @Provides
    @Singleton
    fun provideChildAppPolicyRepository(
        impl: ChildAppPolicyRepositoryImpl,
    ): ChildAppPolicyRepository = impl

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl

    @Provides
    @Singleton
    fun providePolicySettingsRepository(
        impl: PolicySettingsRepositoryImpl,
    ): PolicySettingsRepository = impl

    // Single policy decision point for the whole app; the protection engine
    // depends on the interface, so it must be bound here.
    @Provides
    @Singleton
    fun providePolicyEvaluator(): PolicyEvaluator = DefaultPolicyEvaluator()

    // Group 6: the runtime owns active protection and delegates the process
    // lifecycle (foreground service) to this launcher.
    @Provides
    @Singleton
    fun provideProtectionServiceLauncher(
        @ApplicationContext context: Context,
    ): ProtectionServiceLauncher = AndroidProtectionServiceLauncher(context)

    @Provides
    @Singleton
        fun provideProtectedAppsRepository(impl: ProtectedAppsRepositoryImpl): ProtectedAppsRepository = impl

    @Provides
    @Singleton
    fun provideActivityLogRepository(impl: ActivityLogRepositoryImpl): ActivityLogRepository = impl

    @Provides
    @Singleton
    fun provideResetRepository(impl: ResetRepositoryImpl): ResetRepository = impl

    // Phase 4 Step 1B-2: screen-time usage accounting over the v7 database.
    @Provides
    @Singleton
    fun provideScreenTimeUsageRepository(
        impl: ScreenTimeUsageRepositoryImpl,
    ): ScreenTimeUsageRepository = impl

    // Phase 4 Step 1B-3: what the accounting layer measures and dates against.
    // The device's own zone is what "today" means to the user (see UsageDateKey).
    @Provides
    @Singleton
    fun provideZoneId(): ZoneId = ZoneId.systemDefault()

    // Durations are measured against the monotonic clock, never the wall clock.
    @Provides
    @Singleton
    fun provideElapsedTimeSource(impl: SystemElapsedTimeSource): ElapsedTimeSource = impl

    // Future backend sync: bound to offline no-ops by default; swap these
    // bindings to enable sync without touching any call site.
    @Provides
    @Singleton
    fun provideAccountSyncGateway(impl: NoOpAccountSyncGateway): AccountSyncGateway = impl

    @Provides
    @Singleton
    fun provideChildProfileSyncGateway(impl: NoOpChildProfileSyncGateway): ChildProfileSyncGateway = impl

    @Provides
    @Singleton
    fun provideSettingsSyncGateway(impl: NoOpSettingsSyncGateway): SettingsSyncGateway = impl

    @Provides
    @Singleton
    fun provideSyncCoordinator(coordinator: SyncCoordinator): SyncCoordinator = coordinator

    // Phase 11: durable parent requests + notification dedup/delivery.
    @Provides
    @Singleton
    fun provideParentRequestRepository(impl: ParentRequestRepositoryImpl): ParentRequestRepository = impl

    @Provides
    @Singleton
    fun provideNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository = impl

    @Provides
    @Singleton
    fun provideNotificationPolicy(): NotificationPolicy = DefaultNotificationPolicy()

    /** Phase 12: injectable wall clock (PIN lockout timing is testable). */
    @Provides
    @Singleton
    fun provideClock(): () -> Long = { System.currentTimeMillis() }

    // Phase 12: Keystore-backed biometric-at-rest protection.
    @Provides
    @Singleton
    fun provideAndroidKeystoreKeyProvider(): AndroidKeystoreKeyProvider = AndroidKeystoreKeyProvider()

    @Provides
    @Singleton
    fun provideSecureKeyProvider(impl: AndroidKeystoreKeyProvider): SecureKeyProvider = impl

    @Provides
    @Singleton
    fun provideSecurityStateHolder(): SecurityStateHolder = SecurityStateHolder()

    @Provides
    @Singleton
    fun provideSecureCrypto(keyProvider: SecureKeyProvider): SecureCrypto = AesGcmSecureCrypto(keyProvider)

    @Provides
    @Singleton
    fun provideBiometricTemplateCipher(
        crypto: SecureCrypto,
        securityState: SecurityStateHolder,
    ): BiometricTemplateCipher = KeystoreBiometricTemplateCipher(crypto, securityState)

    @Provides
    @Singleton
    fun provideAppLabelResolver(
        @ApplicationContext context: Context,
    ): AppLabelResolver = AppLabelResolver(context)

    @Provides
    @Singleton
    fun provideNotificationContentFactory(
        @ApplicationContext context: Context,
    ): NotificationContentFactory = AndroidNotificationContentFactory(context)

    @Provides
    @Singleton
    fun provideNotificationDispatcher(
        @ApplicationContext context: Context,
    ): AppNotificationDispatcher = AndroidNotificationDispatcher(context)

    @Provides
    @Singleton
    fun provideNotificationCoordinator(
        policy: NotificationPolicy,
        repository: NotificationRepository,
        dispatcher: AppNotificationDispatcher,
        contentFactory: NotificationContentFactory,
    ): NotificationCoordinator = NotificationCoordinator(policy, repository, dispatcher, contentFactory)

    @Provides
    @Singleton
    fun provideRecognizer(): Recognizer = Recognizer()

    @Provides
    @Singleton
    fun provideFaceEmbeddingModel(@ApplicationContext context: Context): FaceEmbeddingModel =
        TfLiteMobileFaceNet(context)
}
