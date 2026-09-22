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
