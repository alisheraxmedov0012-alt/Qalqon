package uz.faceguard.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 -> v4: adds child-scoped app policies.
 *
 * Purely additive: no existing table is altered or dropped, so accounts,
 * parent/child profiles, protected apps and the activity log all survive the
 * upgrade. The statement mirrors Room's generated schema for
 * [ChildAppPolicyEntity] (column order follows the constructor).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `child_app_policies` (" +
                "`accountId` INTEGER NOT NULL, " +
                "`childId` INTEGER NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`dailyLimitMinutes` INTEGER, " +
                "`activationDelayMs` INTEGER NOT NULL, " +
                "`recoveryDelayMs` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `childId`, `packageName`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_child_app_policies_accountId_childId` " +
                "ON `child_app_policies` (`accountId`, `childId`)",
        )
    }
}

/**
 * v4 -> v5: account-scopes the activity log.
 *
 * `activity_events` gains `accountId` so one account can never see another
 * account's events. Legacy rows had no owner: when the device has exactly one
 * account they are unambiguously that account's and are carried over (their id
 * and timestamp are preserved); with zero or several accounts they cannot be
 * attributed safely and are dropped instead of being shown to the wrong
 * account. The recreate-table statement mirrors Room's generated schema for
 * [ActivityEventEntity] (column order follows the constructor).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_events_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`detail` TEXT, " +
                "`at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO `activity_events_new` (`id`, `accountId`, `type`, `detail`, `at`) " +
                "SELECT `id`, (SELECT `id` FROM `user_accounts` LIMIT 1), `type`, `detail`, `at` " +
                "FROM `activity_events` " +
                "WHERE (SELECT COUNT(*) FROM `user_accounts`) = 1",
        )
        db.execSQL("DROP TABLE `activity_events`")
        db.execSQL("ALTER TABLE `activity_events_new` RENAME TO `activity_events`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_activity_events_at` " +
                "ON `activity_events` (`at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_activity_events_accountId_at` " +
                "ON `activity_events` (`accountId`, `at`)",
        )
    }
}

/**
 * v5 -> v6: adds durable parent requests and notification dedup records.
 *
 * Purely additive: no existing table is altered or dropped, so accounts,
 * profiles, protected apps, policies and the activity log all survive the
 * upgrade. Statements mirror Room's generated schema for [ParentRequestEntity]
 * and [NotificationRecordEntity] (column order follows the constructor).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parent_requests` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, " +
                "`childId` INTEGER NOT NULL, " +
                "`targetPackageName` TEXT NOT NULL, " +
                "`requestType` TEXT NOT NULL, " +
                "`requestedDurationMinutes` INTEGER NOT NULL, " +
                "`approvedDurationMinutes` INTEGER, " +
                "`status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`expiresAt` INTEGER, " +
                "`resolvedAt` INTEGER, " +
                "`resolutionReason` TEXT, " +
                "`source` TEXT NOT NULL, " +
                "`deduplicationKey` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_createdAt` " +
                "ON `parent_requests` (`accountId`, `createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_status_createdAt` " +
                "ON `parent_requests` (`accountId`, `status`, `createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_childId_status` " +
                "ON `parent_requests` (`accountId`, `childId`, `status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_deduplicationKey_status` " +
                "ON `parent_requests` (`accountId`, `deduplicationKey`, `status`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_records` (" +
                "`deduplicationKey` TEXT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`relatedRequestId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`delivered` INTEGER NOT NULL, " +
                "`deliveryAt` INTEGER, " +
                "PRIMARY KEY(`deduplicationKey`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_notification_records_accountId_createdAt` " +
                "ON `notification_records` (`accountId`, `createdAt`)",
        )
    }
}

/**
 * v6 -> v7: adds Phase 4 screen-time persistence.
 *
 * Purely additive: no existing table is altered or dropped, so accounts,
 * profiles, protected apps, policies, activity events, parent requests,
 * notification records and all Phase 12 security data survive the upgrade.
 * Statements mirror Room's generated schema for [DailyAppUsageEntity] and
 * [ChildScreenTimeLimitEntity] (column order follows the constructor).
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_app_usage` (" +
                "`accountId` INTEGER NOT NULL, " +
                "`childId` INTEGER NOT NULL, " +
                "`dateKey` TEXT NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`usedMs` INTEGER NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `childId`, `dateKey`, `packageName`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_daily_app_usage_accountId_childId_dateKey` " +
                "ON `daily_app_usage` (`accountId`, `childId`, `dateKey`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_daily_app_usage_accountId_childId_dateKey_category` " +
                "ON `daily_app_usage` (`accountId`, `childId`, `dateKey`, `category`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `child_screen_time_limits` (" +
                "`accountId` INTEGER NOT NULL, " +
                "`childId` INTEGER NOT NULL, " +
                "`scope` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`limitMinutes` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `childId`, `scope`, `category`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_child_screen_time_limits_accountId_childId` " +
                "ON `child_screen_time_limits` (`accountId`, `childId`)",
        )
    }
}
