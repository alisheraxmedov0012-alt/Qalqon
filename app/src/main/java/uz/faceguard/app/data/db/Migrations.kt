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
