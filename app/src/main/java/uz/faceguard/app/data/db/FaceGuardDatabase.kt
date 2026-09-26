package uz.faceguard.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserAccountEntity::class,
        ParentProfileEntity::class,
        ChildProfileEntity::class,
        ProtectedAppEntity::class,
        ActivityEventEntity::class,
        ChildAppPolicyEntity::class,
        ParentRequestEntity::class,
        NotificationRecordEntity::class,
        DailyAppUsageEntity::class,
        ChildScreenTimeLimitEntity::class,
        UsageSnapshotCheckpointEntity::class,
        ScheduleRuleEntity::class,
        ScheduleAppTargetEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class FaceGuardDatabase : RoomDatabase() {
    abstract fun userAccountDao(): UserAccountDao
    abstract fun parentProfileDao(): ParentProfileDao
    abstract fun childProfileDao(): ChildProfileDao
    abstract fun protectedAppDao(): ProtectedAppDao
    abstract fun activityEventDao(): ActivityEventDao
    abstract fun childAppPolicyDao(): ChildAppPolicyDao
    abstract fun parentRequestDao(): ParentRequestDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun dailyAppUsageDao(): DailyAppUsageDao
    abstract fun childScreenTimeLimitDao(): ChildScreenTimeLimitDao
    abstract fun usageSnapshotCheckpointDao(): UsageSnapshotCheckpointDao
    abstract fun scheduleDao(): ScheduleDao
}
