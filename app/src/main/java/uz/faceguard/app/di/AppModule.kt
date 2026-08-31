package uz.faceguard.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import uz.faceguard.app.data.db.ActivityEventDao
import uz.faceguard.app.data.db.ChildProfileDao
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.db.ParentProfileDao
import uz.faceguard.app.data.db.ProtectedAppDao
import uz.faceguard.app.data.db.UserAccountDao
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.data.repository.ActivityLogRepositoryImpl
import uz.faceguard.app.data.repository.ResetRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentProfileRepositoryImpl
import uz.faceguard.app.data.repository.ProtectedAppsRepositoryImpl
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.data.repository.SettingsRepositoryImpl
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ResetRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
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
            .fallbackToDestructiveMigration() // MVP only; add real migrations before release.
            .build()

    @Provides fun provideUserAccountDao(db: FaceGuardDatabase): UserAccountDao = db.userAccountDao()
    @Provides fun provideParentProfileDao(db: FaceGuardDatabase): ParentProfileDao = db.parentProfileDao()
    @Provides fun provideChildProfileDao(db: FaceGuardDatabase): ChildProfileDao = db.childProfileDao()
    @Provides fun provideProtectedAppDao(db: FaceGuardDatabase): ProtectedAppDao = db.protectedAppDao()
    @Provides fun provideActivityEventDao(db: FaceGuardDatabase): ActivityEventDao = db.activityEventDao()

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
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl

    @Provides
    @Singleton
        fun provideProtectedAppsRepository(impl: ProtectedAppsRepositoryImpl): ProtectedAppsRepository = impl

    @Provides
    @Singleton
    fun provideActivityLogRepository(impl: ActivityLogRepositoryImpl): ActivityLogRepository = impl

    @Provides
    @Singleton
    fun provideResetRepository(impl: ResetRepositoryImpl): ResetRepository = impl

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

    @Provides
    @Singleton
    fun provideRecognizer(): Recognizer = Recognizer()
}
